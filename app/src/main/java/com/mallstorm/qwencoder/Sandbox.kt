package com.mallstorm.qwencoder

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Getting materials out of the Qwen sandbox through the agent itself:
 * the agent packs /workspace into tar.gz, prints it as base64 in numbered parts,
 * the app collects the parts from the terminal output and unpacks them.
 */
object Sandbox {
    const val TAG = "[QCN]"
    private const val PART = 12000
    private val PART_RE = Regex("""QCN_PART (\d+)/(\d+)\s+([A-Za-z0-9+/=\s]+?)(?=QCN_PART|\z)""")

    class Result(val files: LinkedHashMap<String, ByteArray>?, val got: Int, val total: Int)

    fun isService(text: String) = text.trimStart().startsWith(TAG)
    fun isFetch(text: String) = isService(text) && text.contains("qcn.tgz")
    fun isPreview(text: String) = isService(text) && text.contains("превью")

    fun fetchPrompt(): String = """$TAG Служебный запрос приложения Qwen Coder Native: выгрузить файлы проекта. Ничего не меняй в проекте, не делай коммитов, не пиши пояснений.
1) Выполни одну команду:
cd /workspace && find . -type f -not -path './.git/*' -not -path '*/node_modules/*' -not -path '*/.next/*' -not -path '*/.cache/*' -size -768k -print0 | tar --null -czf /tmp/qcn.tgz -T - && base64 -w0 /tmp/qcn.tgz > /tmp/qcn.b64 && echo "parts=$(( ($(wc -c < /tmp/qcn.b64) + ${PART - 1}) / $PART ))"
2) Затем для каждого i от 1 до parts выполни ОТДЕЛЬНОЙ командой (подставь i и parts числами):
i=1; n=PARTS; echo "QCN_PART ${'$'}i/${'$'}n"; cut -c ${'$'}(( (i-1)*$PART+1 ))-${'$'}(( i*$PART )) /tmp/qcn.b64
Выведи все части по порядку, ни одну не пропускай и не сокращай вывод. В конце ответь одним словом: готово."""

    fun previewPrompt(port: Int): String = """$TAG Служебный запрос приложения: запусти превью проекта на порту $port. Ничего не меняй в файлах проекта и не делай коммитов.
Если в /workspace есть package.json со скриптом dev или start — при необходимости установи зависимости и запусти его в фоне на порту $port с хостом 0.0.0.0 (через nohup, вывод в /tmp/qcn-preview.log). Иначе выполни:
cd /workspace && (nohup python3 -m http.server $port --bind 0.0.0.0 > /tmp/qcn-preview.log 2>&1 &)
Затем проверь: sleep 2; curl -sI http://localhost:$port | head -1 — и ответь одной строкой."""

    /** Collects base64 parts printed after the latest fetch request. */
    fun extract(thread: List<ChatMsg>): Result? {
        val start = thread.indexOfLast { it.role == "user" && isFetch(it.text) }
        if (start < 0) return null
        val parts = HashMap<Int, String>()
        var total = 0
        for (m in thread.drop(start + 1)) {
            if (m.role == "user") break
            for (s in m.steps) {
                for (g in PART_RE.findAll(s.log)) {
                    val i = g.groupValues[1].toIntOrNull() ?: continue
                    total = maxOf(total, g.groupValues[2].toIntOrNull() ?: 0)
                    parts[i] = g.groupValues[3].replace(Regex("\\s"), "")
                }
            }
        }
        if (total == 0) return Result(null, 0, 0)
        if ((1..total).any { !parts.containsKey(it) }) return Result(null, parts.size, total)
        return try {
            val raw = Base64.decode((1..total).joinToString("") { parts[it]!! }, Base64.DEFAULT)
            Result(untar(raw), total, total)
        } catch (_: Exception) {
            Result(null, parts.size, total)
        }
    }

    fun untar(tgz: ByteArray): LinkedHashMap<String, ByteArray> {
        val data = GZIPInputStream(ByteArrayInputStream(tgz)).use { it.readBytes() }
        val out = LinkedHashMap<String, ByteArray>()
        var pos = 0
        var longName: String? = null
        fun str(off: Int, len: Int): String {
            var e = off
            while (e < off + len && data[e].toInt() != 0) e++
            return String(data, off, e - off, Charsets.UTF_8)
        }
        while (pos + 512 <= data.size) {
            if (data[pos].toInt() == 0) break
            val name = str(pos, 100)
            val size = str(pos + 124, 12).trim().ifEmpty { "0" }.toLong(8).toInt()
            val type = data[pos + 156].toInt().toChar()
            val prefix = if (String(data, pos + 257, 5, Charsets.US_ASCII) == "ustar") str(pos + 345, 155) else ""
            val body = pos + 512
            if (body + size > data.size) break
            when (type) {
                'L' -> longName = str(body, size)
                '0', '\u0000', '7' -> {
                    var path = longName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
                    path = path.removePrefix("./")
                    if (path.isNotEmpty()) out[path] = data.copyOfRange(body, body + size)
                    longName = null
                }
                else -> longName = null
            }
            pos = body + ((size + 511) / 512) * 512
        }
        return LinkedHashMap(out.toSortedMap())
    }

    private fun cacheFile(ctx: Context, id: String) = File(File(ctx.filesDir, "sandbox").apply { mkdirs() }, "$id.tgz")

    fun saveCache(ctx: Context, id: String, files: Map<String, ByteArray>) {
        try {
            cacheFile(ctx, id).writeBytes(packTgz(files))
        } catch (_: Exception) {
        }
    }

    fun loadCache(ctx: Context, id: String): LinkedHashMap<String, ByteArray>? = try {
        val f = cacheFile(ctx, id)
        if (f.exists()) untar(f.readBytes()) else null
    } catch (_: Exception) {
        null
    }

    /** Minimal ustar writer (used for the local cache and for "save archive"). */
    fun packTgz(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { gz ->
            for ((path, bytes) in files) {
                val nameBytes = path.toByteArray(Charsets.UTF_8)
                if (nameBytes.size > 99) {
                    gz.write(header("././@LongLink", nameBytes.size + 1, 'L'))
                    gz.write(nameBytes); gz.write(0)
                    gz.write(ByteArray((512 - (nameBytes.size + 1) % 512) % 512))
                }
                gz.write(header(String(nameBytes.copyOf(minOf(99, nameBytes.size)), Charsets.UTF_8), bytes.size, '0'))
                gz.write(bytes)
                gz.write(ByteArray((512 - bytes.size % 512) % 512))
            }
            gz.write(ByteArray(1024))
        }
        return bos.toByteArray()
    }

    private fun header(name: String, size: Int, type: Char): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) { val b = s.toByteArray(Charsets.UTF_8); System.arraycopy(b, 0, h, off, minOf(b.size, 100)) }
        put(0, name)
        put(100, "0000644\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, String.format("%011o\u0000", size))
        put(136, String.format("%011o\u0000", System.currentTimeMillis() / 1000))
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        put(257, "ustar\u0000")
        put(263, "00")
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(148, String.format("%06o\u0000 ", sum))
        return h
    }

    fun isText(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return false
        return true
    }

    /** Hides the huge base64 blobs in terminal views. */
    fun prettyLog(log: String): String =
        if (log.contains("QCN_PART")) "(часть архива файлов из песочницы, ${log.length / 1024} КБ)" else log
}
