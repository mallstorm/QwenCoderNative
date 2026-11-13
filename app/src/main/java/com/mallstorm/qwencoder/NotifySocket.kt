package com.mallstorm.qwencoder

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal WebSocket client for wss://coder.qwen.ai/api/v1/notifications/ws.
 * The site pushes {"type":"pull_complete"} when a task finishes; [onPull] is called for it.
 * Reconnects with backoff until [stop].
 */
class NotifySocket(private val api: Api, private val onPull: () -> Unit) {
    @Volatile private var alive = false
    @Volatile private var sock: SSLSocket? = null
    private var worker: Thread? = null
    private val rnd = SecureRandom()

    fun start() {
        if (alive) return
        alive = true
        worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        alive = false
        try { sock?.close() } catch (_: Exception) {}
        worker?.interrupt()
    }

    private fun loop() {
        var delay = 3000L
        while (alive) {
            val t0 = System.currentTimeMillis()
            try {
                session()
            } catch (_: Exception) {
            }
            if (!alive) break
            if (System.currentTimeMillis() - t0 > 60_000) delay = 3000L
            try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
            delay = minOf(delay * 2, 60_000L)
        }
    }

    private fun session() {
        val path = api.wsUrl() ?: return
        val host = "coder.qwen.ai"
        val s = SSLSocketFactory.getDefault().createSocket(host, 443) as SSLSocket
        sock = s
        s.soTimeout = 25_000
        s.startHandshake()
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val key = ByteArray(16).also { rnd.nextBytes(it) }
        val req = "GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: ${Base64.encodeToString(key, Base64.NO_WRAP)}\r\nSec-WebSocket-Version: 13\r\n" +
            "Origin: ${Api.CODER}\r\nUser-Agent: ${Api.UA}\r\nAccept-Language: ru-RU,ru;q=0.9\r\n\r\n"
        out.write(req.toByteArray())
        out.flush()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw java.io.IOException("closed")
            head.append(b.toChar())
            if (head.length > 16_000) throw java.io.IOException("bad handshake")
        }
        if (!head.startsWith("HTTP/1.1 101")) throw java.io.IOException("ws " + head.lineSequence().first())
        val msg = ByteArrayOutputStream()
        try {
            while (alive) {
                val b0: Int
                try {
                    b0 = inp.read()
                } catch (_: SocketTimeoutException) {
                    sendFrame(out, 9, ByteArray(0))
                    continue
                }
                if (b0 < 0) break
                val fin = b0 and 0x80 != 0
                val op = b0 and 0x0F
                val b1 = readByte(inp)
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((readByte(inp) shl 8) or readByte(inp)).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte(inp).toLong() } }
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(inp, it) } else null
                if (len > 8_000_000) throw java.io.IOException("frame too big")
                val data = ByteArray(len.toInt())
                readFully(inp, data)
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (op) {
                    8 -> return
                    9 -> sendFrame(out, 10, data)
                    10 -> {}
                    else -> {
                        msg.write(data)
                        if (fin) {
                            val text = String(msg.toByteArray(), Charsets.UTF_8)
                            msg.reset()
                            if (text.contains("pull_complete")) onPull()
                        }
                    }
                }
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun readByte(i: InputStream): Int {
        val b = i.read()
        if (b < 0) throw java.io.IOException("eof")
        return b
    }

    private fun readFully(i: InputStream, buf: ByteArray) {
        var n = 0
        while (n < buf.size) {
            val r = try { i.read(buf, n, buf.size - n) } catch (_: SocketTimeoutException) { continue }
            if (r < 0) throw java.io.IOException("eof")
            n += r
        }
    }

    @Synchronized
    private fun sendFrame(out: OutputStream, op: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        val bos = ByteArrayOutputStream()
        bos.write(0x80 or op)
        if (payload.size < 126) bos.write(0x80 or payload.size)
        else { bos.write(0x80 or 126); bos.write(payload.size shr 8); bos.write(payload.size and 0xFF) }
        bos.write(mask)
        for (i in payload.indices) bos.write(payload[i].toInt() xor mask[i % 4].toInt())
        out.write(bos.toByteArray())
        out.flush()
    }
}
