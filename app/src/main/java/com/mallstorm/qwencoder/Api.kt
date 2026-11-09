package com.mallstorm.qwencoder

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ApiError(val code: String, message: String, val http: Int = 0) : Exception(message)

data class Env(
    val id: String, val name: String, val repo: String, val branches: List<String>,
    val isPrivate: Boolean = false, val pushed: String = "", val description: String = ""
)
data class TaskItem(
    val id: String, val title: String, val status: String, val updatedAt: Long,
    val repo: String, val branch: String
)
data class Step(val id: String, val type: String, val action: String, val log: String, val status: String)
data class ChatMsg(
    val id: String, val role: String, val text: String, val steps: List<Step>,
    val thinking: String, val error: String?, val done: Boolean
)

/** Extracts Qwen tokens from whatever the user pastes (raw JWT, "refresh_token=...", Cookie header, JSON export). */
object TokenParser {
    private val jwtRe = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")

    fun payload(t: String): JSONObject? = try {
        val p = t.split(".")[1]
        JSONObject(String(Base64.decode(p, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /** Returns (access, refresh); either may be null. */
    fun extract(text: String): Pair<String?, String?> {
        var access: String? = null
        var refresh: String? = null
        val named = Regex("refresh_token[\"']?\\s*[=:]\\s*[\"']?(eyJ[A-Za-z0-9_.-]+)").find(text)?.groupValues?.get(1)
        if (named != null) refresh = named
        for (m in jwtRe.findAll(text)) {
            val tok = m.value
            when (payload(tok)?.optString("type")) {
                "refresh_token" -> if (refresh == null) refresh = tok
                "access_token" -> if (access == null) access = tok
                else -> if (refresh == null && access == null) refresh = tok
            }
        }
        return Pair(access, refresh)
    }
}

class Api(ctx: Context) {
    companion object {
        const val CODER = "https://coder.qwen.ai"
        const val AUTH = "https://auth.qwen.ai"
        const val VERSION = "0.0.33"
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"
    }

    private val prefs = ctx.getSharedPreferences("qwen_auth", Context.MODE_PRIVATE)
    private val jar = HashMap<String, String>()

    @Volatile var accessToken: String? = prefs.getString("access", null)
    @Volatile var refreshToken: String? = prefs.getString("refresh", null)
    @Volatile var userName: String = prefs.getString("name", "") ?: ""
    @Volatile var userEmail: String = prefs.getString("email", "") ?: ""
    @Volatile var sseConn: HttpURLConnection? = null

    val loggedIn: Boolean get() = !refreshToken.isNullOrEmpty() || !accessToken.isNullOrEmpty()

    @Synchronized
    fun setTokens(access: String?, refresh: String?) {
        if (access != null) accessToken = access
        if (refresh != null) refreshToken = refresh
        prefs.edit().putString("access", accessToken).putString("refresh", refreshToken).apply()
    }

    @Synchronized
    fun logout() {
        accessToken = null
        refreshToken = null
        userName = ""
        userEmail = ""
        jar.clear()
        prefs.edit().clear().apply()
    }

    // ---------- low level ----------

    private fun tzHeader(): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.ENGLISH).format(Date())

    private fun open(method: String, url: String, access: String?, sse: Boolean, extraCookie: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = if (sse) 0 else 45000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", if (sse) "text/event-stream" else "application/json")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
        c.setRequestProperty("Origin", CODER)
        c.setRequestProperty("Referer", "$CODER/")
        c.setRequestProperty("source", "web")
        c.setRequestProperty("version", VERSION)
        c.setRequestProperty("x-request-id", UUID.randomUUID().toString())
        c.setRequestProperty("x-request-origin", CODER)
        c.setRequestProperty("timezone", tzHeader())
        c.setRequestProperty("bx-v", "2.5.37")
        if (access != null) c.setRequestProperty("Authorization", "Bearer $access")
        if (sse) {
            c.setRequestProperty("x-accel-buffering", "no")
            c.setRequestProperty("Accept-Encoding", "identity")
        }
        val cookies = ArrayList<String>()
        if (extraCookie != null) cookies.add(extraCookie)
        synchronized(jar) { for ((k, v) in jar) cookies.add("$k=$v") }
        if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.joinToString("; "))
        return c
    }

    private fun saveCookies(c: HttpURLConnection) {
        val list = c.headerFields["Set-Cookie"] ?: return
        synchronized(jar) {
            for (sc in list) {
                val nv = sc.substringBefore(';')
                val k = nv.substringBefore('=').trim()
                val v = nv.substringAfter('=', "").trim()
                if (k == "refresh_token") continue
                if (v.isEmpty() || v == "\"\"") jar.remove(k) else jar[k] = v
            }
        }
    }

    private class Raw(val code: Int, val body: String)

    private fun exec(method: String, url: String, body: String?, access: String?, extraCookie: String? = null): Raw {
        val c = open(method, url, access, false, extraCookie)
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            saveCookies(c)
            val s = if (code in 200..399) c.inputStream else c.errorStream
            val text = s?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Raw(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun unauthorized(r: Raw): Boolean {
        if (r.code == 401) return true
        return try {
            val j = JSONObject(r.body)
            !j.optBoolean("success", true) && j.optJSONObject("data")?.optString("code") == "Unauthorized"
        } catch (_: Exception) {
            false
        }
    }

    private fun fail(r: Raw): Nothing {
        var msg = "HTTP ${r.code}"
        try {
            val j = JSONObject(r.body)
            val d = j.optJSONObject("data")
            val det = d?.optString("details").orEmpty().ifEmpty { d?.optString("message").orEmpty() }
            if (det.isNotEmpty()) msg = det
        } catch (_: Exception) {
        }
        throw ApiError(if (r.code == 401) "auth" else "http", msg, r.code)
    }

    // ---------- auth ----------

    @Synchronized
    fun refresh() {
        val rt = refreshToken
        if (rt.isNullOrEmpty()) throw ApiError("auth", "Нет refresh-токена. Войдите заново.")
        val r = exec("GET", "$AUTH/api/v2/auths/refresh", null, null, "refresh_token=$rt")
        val j = try { JSONObject(r.body) } catch (_: Exception) { fail(r) }
        if (!j.optBoolean("success")) throw ApiError("auth", j.optJSONObject("data")?.optString("details").orEmpty().ifEmpty { "Сессия истекла" }, r.code)
        val d = j.getJSONObject("data")
        val na = d.optString("access_token")
        val nr = d.optString("refresh_token")
        setTokens(if (na.isNotEmpty()) na else null, if (nr.isNotEmpty()) nr else null)
        val name = d.optString("name")
        val email = d.optString("email")
        if (name.isNotEmpty() || email.isNotEmpty()) {
            userName = name
            userEmail = email
            prefs.edit().putString("name", name).putString("email", email).apply()
        }
    }

    @Synchronized
    fun ensureAccess(force: Boolean = false): String {
        val a = accessToken
        if (!force && a != null) {
            val exp = TokenParser.payload(a)?.optLong("exp", 0L) ?: 0L
            if (exp == 0L || exp * 1000 - System.currentTimeMillis() > 60_000) return a
        }
        if (refreshToken.isNullOrEmpty()) {
            if (a != null) return a
            throw ApiError("auth", "Не выполнен вход")
        }
        refresh()
        return accessToken ?: throw ApiError("auth", "Не удалось обновить токен")
    }

    private fun call(method: String, url: String, body: JSONObject? = null): JSONObject {
        var r = exec(method, url, body?.toString(), ensureAccess())
        if (unauthorized(r) && !refreshToken.isNullOrEmpty()) {
            r = exec(method, url, body?.toString(), ensureAccess(true))
        }
        if (r.code !in 200..299) fail(r)
        val j = try { JSONObject(r.body) } catch (_: Exception) { throw ApiError("parse", "Неверный ответ сервера") }
        if (!j.optBoolean("success", true)) fail(r)
        return j
    }

    // ---------- endpoints ----------

    fun me() {
        val d = call("GET", "$AUTH/api/v2/auths/").optJSONObject("data") ?: return
        userName = d.optString("name")
        userEmail = d.optString("email")
        prefs.edit().putString("name", userName).putString("email", userEmail).apply()
    }

    fun environments(): List<Env> {
        val d = call("GET", "$CODER/coder/api/v2/environments/?page_num=1&page_size=50").optJSONObject("data")
        val arr = d?.optJSONArray("environments") ?: return emptyList()
        val out = ArrayList<Env>()
        for (i in 0 until arr.length()) out.add(parseEnv(arr.getJSONObject(i)))
        return out
    }

    private fun parseEnv(e: JSONObject): Env {
        val info = e.optJSONObject("meta")?.optJSONObject("repo_info")
        val bs = ArrayList<String>()
        val ba = info?.optJSONArray("branches")
        if (ba != null) for (k in 0 until ba.length()) bs.add(ba.getJSONObject(k).optString("name"))
        return Env(
            e.optString("id"), e.optString("name"), e.optString("repo_name"), bs,
            info?.optBoolean("private") ?: false,
            info?.optString("pushed_at").orEmpty(),
            info?.optString("description").orEmpty().let { if (it == "null") "" else it }
        )
    }

    /** Fresh data for one repository (up-to-date branch list). */
    fun environment(id: String): Env {
        val d = call("GET", "$CODER/coder/api/v2/environments/$id").optJSONObject("data") ?: throw ApiError("parse", "Пустой ответ")
        return parseEnv(d)
    }

    /** Whether the Qwen account has a GitHub connection ("connected"), null if unknown. */
    fun githubConnected(): Boolean? = try {
        call("GET", "$CODER/coder/api/v2/github/connection_status").optJSONObject("data")
            ?.optString("connection_status")?.let { it == "connected" }
    } catch (_: Exception) {
        null
    }

    private fun parseTask(t: JSONObject): TaskItem {
        val info = t.optJSONObject("meta")?.optJSONObject("repo_info")
        return TaskItem(
            t.optString("id"), t.optString("title"), t.optString("status"), t.optLong("updated_at"),
            info?.optString("repository").orEmpty(), info?.optString("selected_branch").orEmpty()
        )
    }

    fun tasks(page: Int = 1): List<TaskItem> {
        val arr = call("GET", "$CODER/coder/api/v2/task/list?page=$page").optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
    }

    /** Returns the task "data" object (title, task.history, meta, ...). */
    fun taskDetail(id: String): JSONObject =
        call("GET", "$CODER/coder/api/v2/task/$id").optJSONObject("data") ?: JSONObject()

    fun taskStatus(id: String): String =
        call("GET", "$CODER/coder/api/v2/task/$id/status").optJSONObject("data")?.optString("status").orEmpty()

    fun newTask(env: Env?, branch: String, model: String): String {
        val meta = JSONObject()
        if (env != null) {
            meta.put("repo_info", JSONObject().put("repository", env.repo).put("git_provider", "github").put("selected_branch", branch))
        }
        val body = JSONObject()
            .put("chatId", "")
            .put("models", JSONArray().put(model))
            .put("chat_type", "code")
            .put("title", "Новый чат")
            .put("chat_mode", "normal")
            .put("timestamp", System.currentTimeMillis())
            .put("env_id", env?.id ?: "")
            .put("meta", meta)
        return call("POST", "$CODER/coder/api/v2/task/new", body).getJSONObject("data").getString("id")
    }

    fun deleteTask(id: String) {
        call("DELETE", "$CODER/coder/api/v2/task/$id")
    }

    /** Streams SSE "data:" payloads of a completion. Blocking; run off the main thread. */
    fun stream(chatId: String, text: String, parentId: String?, model: String, onData: (String) -> Unit) {
        var attempt = 0
        while (true) {
            val access = ensureAccess(attempt > 0)
            val ts = System.currentTimeMillis() / 1000
            val pid: Any = parentId ?: JSONObject.NULL
            val msg = JSONObject()
                .put("id", JSONObject.NULL)
                .put("fid", UUID.randomUUID().toString())
                .put("parentId", pid)
                .put("childrenIds", JSONArray().put(UUID.randomUUID().toString()))
                .put("role", "user")
                .put("content", text)
                .put("user_action", "chat")
                .put("files", JSONArray())
                .put("timestamp", ts)
                .put("models", JSONArray().put(model))
                .put("model", "")
                .put("chat_type", "code")
                .put("feature_config", JSONObject().put("thinking_enabled", false).put("output_schema", "phase"))
                .put("extra", JSONObject().put("meta", JSONObject().put("subChatType", "code")))
                .put("sub_chat_type", "code")
                .put("parent_id", pid)
            val body = JSONObject()
                .put("stream", true)
                .put("version", "2.1")
                .put("incremental_output", true)
                .put("chatId", chatId)
                .put("parentId", parentId ?: "")
                .put("chat_id", chatId)
                .put("chat_mode", "normal")
                .put("model", model)
                .put("parent_id", pid)
                .put("messages", JSONArray().put(msg))
                .put("timestamp", ts)
            val c = open("POST", "$CODER/coder/api/v2/task/completions?chat_id=$chatId", access, true, null)
            sseConn = c
            try {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                saveCookies(c)
                val ctype = c.contentType.orEmpty()
                if (code !in 200..299 || !ctype.contains("event-stream")) {
                    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    val raw = Raw(code, txt)
                    if (attempt == 0 && unauthorized(raw) && !refreshToken.isNullOrEmpty()) {
                        attempt++
                        continue
                    }
                    if (code in 200..299 && txt.isNotBlank() && !ctype.contains("json")) {
                        onData(txt)
                        return
                    }
                    fail(raw)
                }
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("data:")) {
                            val d = line.substring(5).trim()
                            if (d.isNotEmpty() && d != "[DONE]") onData(d)
                        }
                    }
                }
                return
            } finally {
                sseConn = null
                c.disconnect()
            }
        }
    }

    fun cancelStream() {
        try {
            sseConn?.disconnect()
        } catch (_: Exception) {
        }
    }

    // ---------- parsing ----------

    fun mergeSteps(into: LinkedHashMap<String, Step>, logList: JSONArray?) {
        if (logList == null) return
        for (i in 0 until logList.length()) {
            val l = logList.optJSONObject(i) ?: continue
            val role = l.optString("role")
            if (role.isNotEmpty() && role != "function") continue
            val id = l.optString("function_id").ifEmpty { "step$i" }
            val old = into[id]
            if (old == null && l.optString("function_type").isEmpty()) continue
            val log = l.optString("log")
            into[id] = Step(
                id,
                l.optString("function_type").ifEmpty { old?.type.orEmpty() },
                l.optString("action").ifEmpty { l.optString("content").ifEmpty { old?.action.orEmpty() } },
                if (log.isNotEmpty()) log else old?.log.orEmpty(),
                l.optString("status").ifEmpty { old?.status.orEmpty() }
            )
        }
    }

    fun parseThread(data: JSONObject): List<ChatMsg> {
        val hist = data.optJSONObject("task")?.optJSONObject("history") ?: return emptyList()
        val msgs = hist.optJSONObject("messages") ?: return emptyList()
        var cur: String = if (hist.isNull("currentId")) (if (data.isNull("currentId")) "" else data.optString("currentId")) else hist.optString("currentId")
        val chain = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        while (cur.isNotEmpty() && msgs.has(cur) && seen.add(cur)) {
            val m = msgs.getJSONObject(cur)
            chain.add(m)
            if (m.isNull("parentId")) break
            cur = m.optString("parentId")
        }
        chain.reverse()
        return chain.map { m ->
            val role = m.optString("role")
            if (role == "user") {
                ChatMsg(m.optString("id"), "user", m.optString("content"), emptyList(), "", null, true)
            } else {
                val answer = StringBuilder()
                val think = StringBuilder()
                val steps = LinkedHashMap<String, Step>()
                val cl = m.optJSONArray("content_list")
                if (cl != null) {
                    for (i in 0 until cl.length()) {
                        val it = cl.optJSONObject(i) ?: continue
                        val phase = it.optString("phase")
                        val content = it.optString("content")
                        when {
                            phase == "answer" -> answer.append(content)
                            phase.startsWith("think") -> think.append(content)
                            else -> mergeSteps(steps, it.optJSONObject("extra")?.optJSONArray("log_list"))
                        }
                    }
                }
                if (answer.isEmpty()) {
                    val plain = m.optString("content")
                    if (plain.isNotEmpty() && plain != "null") answer.append(plain)
                }
                val err = m.optJSONObject("error")?.let { e ->
                    e.optString("details").ifEmpty { e.optString("code") }
                }
                ChatMsg(m.optString("id"), "assistant", answer.toString(), steps.values.toList(), think.toString(), err, m.optBoolean("done"))
            }
        }
    }
}
