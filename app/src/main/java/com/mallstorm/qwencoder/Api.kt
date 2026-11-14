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

data class BranchMeta(val updatedAt: String = "", val createdAt: String = "", val message: String = "")
data class Env(
    val id: String, val name: String, val repo: String, val branches: List<String>,
    val isPrivate: Boolean = false, val pushed: String = "", val description: String = "",
    val branchMeta: Map<String, BranchMeta> = emptyMap()
)
data class TaskItem(
    val id: String, val title: String, val status: String, val updatedAt: Long,
    val repo: String, val branch: String
)
data class Step(
    val id: String, val type: String, val action: String, val log: String, val status: String,
    val args: String = "", val tool: String = ""
)
data class FileChange(val path: String, val command: String, val oldText: String, val newText: String, val pending: Boolean = false, val full: Boolean = true)
data class GhAccount(val installationId: Long, val login: String, val type: String)
data class GhRepo(
    val id: Long, val fullName: String, val name: String, val isPrivate: Boolean,
    val description: String, val language: String, val pushed: String
)
data class Usage(val remaining: Int, val status: String, val member: String, val resetAt: Long)
data class ChatMsg(
    val id: String, val role: String, val text: String, val steps: List<Step>,
    val thinking: String, val error: String?, val done: Boolean,
    val files: List<FileChange> = emptyList(), val commit: String = "", val work: String = "",
    val parent: String = "", val sibs: List<String> = emptyList()
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
        const val VERSION = "0.0.34"
        const val GH_REDIRECT = "https://coder.qwen.ai/setting/caenvironment/new"
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"
    }

    private val prefs = ctx.getSharedPreferences("qwen_auth", Context.MODE_PRIVATE)
    private val jar = HashMap<String, String>()

    @Volatile var accessToken: String? = null
    @Volatile var refreshToken: String? = null
    @Volatile var userName: String = ""
    @Volatile var userEmail: String = ""
    @Volatile var sseConn: HttpURLConnection? = null

    // ---------- accounts (any number of Qwen accounts) ----------

    data class Account(val key: String, val name: String, val email: String)

    private var activeKey: String = ""
    private var prevKey: String? = null

    init {
        // migrate the single-account storage of older versions
        if (!prefs.contains("accounts") && (prefs.contains("refresh") || prefs.contains("access"))) {
            val a = prefs.getString("access", null)
            val r = prefs.getString("refresh", null)
            val key = keyOf(r ?: a) ?: UUID.randomUUID().toString()
            val o = JSONObject().put("key", key).put("access", a).put("refresh", r)
                .put("name", prefs.getString("name", "")).put("email", prefs.getString("email", ""))
            prefs.edit().putString("accounts", JSONArray().put(o).toString()).putString("active", key)
                .remove("access").remove("refresh").remove("name").remove("email").apply()
        }
        activeKey = prefs.getString("active", "") ?: ""
        loadActive()
    }

    private fun keyOf(tok: String?): String? = tok?.let { TokenParser.payload(it)?.optString("id")?.ifEmpty { null } }

    private fun store(): JSONArray = try { JSONArray(prefs.getString("accounts", "[]")) } catch (_: Exception) { JSONArray() }

    private fun find(arr: JSONArray, key: String): Int {
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("key") == key) return i
        return -1
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }

    private fun loadActive() {
        val arr = store()
        val i = find(arr, activeKey)
        val o = if (i >= 0) arr.getJSONObject(i) else null
        accessToken = o?.let { str(it, "access") }
        refreshToken = o?.let { str(it, "refresh") }
        userName = o?.let { str(it, "name") }.orEmpty()
        userEmail = o?.let { str(it, "email") }.orEmpty()
        synchronized(jar) { jar.clear() }
    }

    @Synchronized
    private fun persist() {
        if (activeKey.isEmpty()) return
        val arr = store()
        val o = JSONObject().put("key", activeKey).put("access", accessToken).put("refresh", refreshToken)
            .put("name", userName).put("email", userEmail)
        val i = find(arr, activeKey)
        if (i >= 0) arr.put(i, o) else arr.put(o)
        prefs.edit().putString("accounts", arr.toString()).putString("active", activeKey).apply()
    }

    fun accounts(): List<Account> {
        val arr = store()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Account(it.optString("key"), str(it, "name").orEmpty(), str(it, "email").orEmpty()) }
    }

    val activeAccount: String get() = activeKey

    @Synchronized
    fun switchTo(key: String) {
        if (find(store(), key) < 0) return
        cancelStream()
        activeKey = key
        prefs.edit().putString("active", key).apply()
        loadActive()
    }

    /** Starts signing in another account; the current one stays saved. */
    @Synchronized
    fun beginAdd() {
        prevKey = activeKey.ifEmpty { null }
        activeKey = ""
        accessToken = null
        refreshToken = null
        userName = ""
        userEmail = ""
        synchronized(jar) { jar.clear() }
    }

    /** Saves the freshly signed-in account (replaces it if it was already in the list). */
    @Synchronized
    fun finishAdd() {
        val key = keyOf(refreshToken ?: accessToken) ?: UUID.randomUUID().toString()
        activeKey = key
        prevKey = null
        persist()
    }

    @Synchronized
    fun cancelAdd() {
        activeKey = prevKey ?: prefs.getString("active", "").orEmpty()
        prevKey = null
        loadActive()
    }

    val loggedIn: Boolean get() = !refreshToken.isNullOrEmpty() || !accessToken.isNullOrEmpty()

    @Synchronized
    fun setTokens(access: String?, refresh: String?) {
        if (access != null) accessToken = access
        if (refresh != null) refreshToken = refresh
        persist()
    }

    /** Removes only the current account. Other accounts remain saved but are never selected automatically. */
    @Synchronized
    fun logout() {
        val arr = store()
        val i = find(arr, activeKey)
        if (i >= 0) arr.remove(i)
        activeKey = ""
        prefs.edit().putString("accounts", arr.toString()).putString("active", activeKey).apply()
        loadActive()
    }

    // ---------- low level ----------

    private fun tzHeader(): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.ENGLISH).format(Date())

    private fun open(method: String, url: String, access: String?, sse: Boolean, extraCookie: String?, useJar: Boolean = true): HttpURLConnection {
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
        if (useJar) synchronized(jar) { for ((k, v) in jar) cookies.add("$k=$v") }
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

    private fun exec(method: String, url: String, body: String?, access: String?, extraCookie: String? = null, useJar: Boolean = true): Raw {
        val c = open(method, url, access, false, extraCookie, useJar)
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (useJar) saveCookies(c)
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
        if (activeKey.isNotEmpty()) {
            val arr = store()
            val i = find(arr, activeKey)
            if (i >= 0) str(arr.getJSONObject(i), "refresh")?.let { refreshToken = it }
        }
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
            persist()
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
        persist()
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
        val metadata = LinkedHashMap<String, BranchMeta>()
        val ba = info?.optJSONArray("branches")
        if (ba != null) for (k in 0 until ba.length()) {
            val branch = ba.optJSONObject(k) ?: continue
            val name = branch.optString("name")
            if (name.isBlank()) continue
            bs.add(name)
            val commit = branch.optJSONObject("commit")
            fun nonNull(o: JSONObject?, key: String): String = if (o == null || o.isNull(key)) "" else o.optString(key)
            val updated = listOf(
                nonNull(branch, "last_commit_at"), nonNull(branch, "last_commit_date"),
                nonNull(branch, "updated_at"), nonNull(branch, "pushed_at"),
                nonNull(commit, "date"), nonNull(commit?.optJSONObject("commit")?.optJSONObject("committer"), "date")
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            metadata[name] = BranchMeta(
                updatedAt = updated,
                createdAt = nonNull(branch, "created_at"),
                message = nonNull(commit, "message").ifBlank { nonNull(branch, "last_commit_message") }
            )
        }
        return Env(
            e.optString("id"), e.optString("name"), e.optString("repo_name"), bs,
            info?.optBoolean("private") ?: false,
            info?.optString("pushed_at").orEmpty(),
            info?.optString("description").orEmpty().let { if (it == "null") "" else it },
            metadata
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

    /** GitHub accounts (app installations) connected to Qwen Coder. */
    fun githubAccounts(): List<GhAccount> {
        val arr = call("GET", "$CODER/coder/api/v2/github/organizations").optJSONObject("data")?.optJSONArray("memberships") ?: return emptyList()
        return (0 until arr.length()).map {
            val m = arr.getJSONObject(it)
            GhAccount(m.optLong("installation_id"), m.optString("account_login"), m.optString("account_type"))
        }
    }

    fun githubRepos(installationId: Long): List<GhRepo> {
        val arr = call("GET", "$CODER/coder/api/v2/github/installations/$installationId/repositories").optJSONObject("data")?.optJSONArray("repositories") ?: return emptyList()
        fun s(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)
        return (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            GhRepo(r.optLong("id"), s(r, "full_name"), s(r, "name"), r.optBoolean("private"), s(r, "description"), s(r, "language"), s(r, "pushed_at"))
        }.sortedByDescending { it.pushed }
    }

    /** Adds a GitHub repository to Qwen Coder (creates an environment). */
    fun createEnvironment(repo: GhRepo, installationId: Long): Env {
        val body = JSONObject().put("name", repo.name).put("description", "").put("env_vars", JSONObject())
            .put("setup_script", "").put("repo_id", repo.id.toString()).put("repo_owner_id", installationId.toString())
        val d = call("POST", "$CODER/coder/api/v2/environments/create", body).optJSONObject("data") ?: throw ApiError("parse", "Пустой ответ")
        return parseEnv(d)
    }

    fun githubInstallUrl(): String {
        val redirect = java.net.URLEncoder.encode(GH_REDIRECT, "UTF-8")
        return call("GET", "$CODER/coder/api/v2/github/install/url?redirect_uri=$redirect").optJSONObject("data")?.optString("redirect_url").orEmpty()
    }

    fun githubValidate(code: String, installationId: String, setupAction: String, state: String) {
        call("POST", "$CODER/coder/api/v2/github/validate", JSONObject().put("code", code)
            .put("installation_id", installationId).put("setup_action", setupAction).put("state", state))
    }

    fun running(): List<TaskItem> {
        val arr = call("GET", "$CODER/coder/api/v2/task/running").optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
    }

    fun pinned(): List<TaskItem> {
        val arr = call("GET", "$CODER/coder/api/v2/task/pinned?page=1").optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
    }

    fun getSettings(): JSONObject = call("GET", "$CODER/api/v2/users/user/settings").optJSONObject("data") ?: JSONObject()

    fun updateSettings(s: JSONObject) {
        call("POST", "$CODER/api/v2/users/user/settings/update", s)
    }

    private fun parseUsage(body: String): Usage? {
        val d = JSONObject(body).optJSONObject("data") ?: return null
        return Usage(d.optInt("remainingPercent", -1), d.optString("status"), d.optString("memberType"), d.optLong("resetAt"))
    }

    private val usageUrl = "https://sg-entitlement.qwen.com/api/entitlement/agent/usage?biz_id=ai_qwen&chat_client=h5&device=pc&fr=pc&pr=qwen-i18n&la=ru-RU&tz=Etc%2FGMT-3&wv=$VERSION"

    fun usage(): Usage? = try {
        parseUsage(exec("GET", usageUrl, null, ensureAccess(), null, false).body)
    } catch (_: Exception) {
        null
    }

    /** Parameters for the notifications WebSocket. */
    fun wsUrl(): String? = try {
        val t = ensureAccess()
        val uid = TokenParser.payload(t)?.optString("id").orEmpty()
        var dev = prefs.getString("device_id", null)
        if (dev == null) { dev = UUID.randomUUID().toString(); prefs.edit().putString("device_id", dev).apply() }
        "/api/v1/notifications/ws?user_id=$uid&device_type=web&device_id=$dev&xid=${UUID.randomUUID()}&token=$t"
    } catch (_: Exception) {
        null
    }

    private class VFile(val path: String) {
        var base: String? = null
        var cur: String? = null
        val oldH = StringBuilder()
        val newH = StringBuilder()
    }

    private fun arg(a: JSONObject, vararg keys: String): String? {
        for (k in keys) if (a.has(k) && !a.isNull(k)) return a.optString(k)
        return null
    }

    fun normPath(p: String): String = p.removePrefix("/workspace/").removePrefix("./")

    /** Rebuilds file changes from the agent's tool-call arguments (used when the server sends no file_list). */
    fun changesFromSteps(steps: Collection<Step>): List<FileChange> {
        val map = LinkedHashMap<String, VFile>()
        for (s in steps) {
            if (s.args.isEmpty()) continue
            try {
                val a = JSONObject(s.args)
                val path = arg(a, "path", "file_path", "filepath", "filename", "file", "target_file") ?: continue
                if (path.isEmpty()) continue
                val content = arg(a, "content", "file_text", "new_content", "contents", "text", "code")
                val old = arg(a, "context", "old_str", "old_string", "old_text", "search", "old_content")
                val new = arg(a, "replacement", "new_str", "new_string", "new_text", "replace")
                if (content == null && new == null) continue
                val v = map.getOrPut(normPath(path)) { VFile(normPath(path)) }
                if (new == null || old.isNullOrEmpty()) {
                    if (v.cur == null && v.base == null) v.base = ""
                    v.cur = new ?: content
                } else {
                    val c = v.cur
                    val at = c?.indexOf(old) ?: -1
                    if (c != null && at >= 0) {
                        v.cur = c.substring(0, at) + new + c.substring(at + old.length)
                    } else {
                        v.oldH.append(old).append("\n")
                        v.newH.append(new).append("\n")
                    }
                }
            } catch (_: Exception) {
            }
        }
        val out = ArrayList<FileChange>()
        for (v in map.values) {
            val c = v.cur
            if (c != null) out.add(FileChange(v.path, "modify", v.base.orEmpty(), c, false, true))
            else if (v.newH.isNotEmpty()) out.add(FileChange(v.path, "patch", v.oldH.toString(), v.newH.toString(), false, false))
        }
        return out
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

    /** Port of the sandbox dev server (previewUrl is http://localhost:PORT). */
    fun sandboxPort(id: String): Int = try {
        val u = call("GET", "$CODER/coder/api/v2/task/sandbox/session?task_id=$id").optJSONObject("data")?.optString("previewUrl").orEmpty()
        Regex(":(\\d+)").find(u)?.groupValues?.get(1)?.toInt() ?: 4001
    } catch (_: Exception) {
        4001
    }

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
    fun stream(chatId: String, text: String, parentId: String?, model: String, thinking: Boolean = false, onData: (String) -> Unit) {
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
                .put("feature_config", JSONObject().put("thinking_enabled", thinking).put("output_schema", "phase"))
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

    /** Re-attaches to a running response; the server replays it from the start (used after the app was away). */
    fun attach(chatId: String, responseId: String, onData: (String) -> Unit) {
        val c = open("GET", "$CODER/coder/api/v2/task/completions?chat_id=$chatId&response_id=$responseId", ensureAccess(), true, null)
        sseConn = c
        try {
            val code = c.responseCode
            saveCookies(c)
            if (code !in 200..299 || !c.contentType.orEmpty().contains("event-stream")) {
                fail(Raw(code, (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""))
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
        } finally {
            sseConn = null
            c.disconnect()
        }
    }

    /** One tool step from the live stream (delta.extra.function). */
    fun mergeStreamStep(into: LinkedHashMap<String, Step>, fn: JSONObject, content: String) {
        val id = fn.optString("function_id")
        if (id.isEmpty()) return
        val old = into[id]
        val status = fn.optString("status").ifEmpty { old?.status.orEmpty() }
        val fin = status == "finished" || status == "failed" || status == "error"
        val action = fn.optString("action").ifEmpty { old?.action ?: content }
        val out = fn.optString("content").ifEmpty { if (fin) content else "" }
        into[id] = Step(id, fn.optString("function_type").ifEmpty { old?.type.orEmpty() }, action,
            if (out.isNotEmpty()) out else old?.log.orEmpty(), status, old?.args.orEmpty(), old?.tool.orEmpty())
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
            val status = l.optString("status").ifEmpty { old?.status.orEmpty() }
            val finalState = status == "finished" || status == "done" || status == "success" || status == "completed" || status == "failed" || status == "error"
            val act = l.optString("action")
            val content = l.optString("content")
            val logField = l.optString("log")
            val action = if (act.isNotEmpty()) act else if (old != null) old.action else content
            // finished entries carry the command output / tool result in "content"
            val log = if (finalState && content.isNotEmpty()) content else if (logField.isNotEmpty()) logField else old?.log.orEmpty()
            val fn = l.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
            val args = fn?.optString("arguments").orEmpty()
            val tool = fn?.optString("name").orEmpty().ifEmpty { l.optString("tool_name") }
            into[id] = Step(
                id,
                l.optString("function_type").ifEmpty { old?.type.orEmpty() },
                action, log, status,
                if (args.isNotEmpty()) args else old?.args.orEmpty(),
                if (tool.isNotEmpty()) tool else old?.tool.orEmpty()
            )
        }
    }

    private class Acc {
        val files = LinkedHashMap<String, FileChange>()
        var commit = ""
    }

    private fun collect(o: Any?, acc: Acc) {
        when (o) {
            is JSONObject -> {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.opt(k)
                    if (k == "file_list" && v is JSONArray) {
                        for (i in 0 until v.length()) {
                            val f = v.optJSONObject(i) ?: continue
                            val path = f.optString("path")
                            if (path.isEmpty()) continue
                            acc.files[path] = FileChange(path, f.optString("command"), f.optString("old_content").let { if (it == "null") "" else it }, f.optString("new_content").let { if (it == "null") "" else it })
                        }
                    } else if (k == "commit_id" && v is String && v.isNotEmpty()) {
                        acc.commit = v
                    } else {
                        collect(v, acc)
                    }
                }
            }
            is JSONArray -> for (i in 0 until o.length()) collect(o.opt(i), acc)
        }
    }

    fun parseThread(data: JSONObject, choice: Map<String, String> = emptyMap()): List<ChatMsg> {
        val hist = data.optJSONObject("task")?.optJSONObject("history") ?: return emptyList()
        val msgs = hist.optJSONObject("messages") ?: return emptyList()
        val cur: String = if (hist.isNull("currentId")) (if (data.isNull("currentId")) "" else data.optString("currentId")) else hist.optString("currentId")
        // path of the saved "current" branch
        val onCur = HashSet<String>()
        run {
            var c = cur
            while (c.isNotEmpty() && msgs.has(c) && onCur.add(c)) {
                val m = msgs.getJSONObject(c)
                if (m.isNull("parentId")) break
                c = m.optString("parentId")
            }
        }
        val childMap = HashMap<String, ArrayList<Pair<Long, String>>>()
        run {
            val it = msgs.keys()
            while (it.hasNext()) {
                val k = it.next()
                val m = msgs.optJSONObject(k) ?: continue
                val pid = if (m.isNull("parentId")) "" else m.optString("parentId")
                if (pid.isEmpty() && m.optString("role") != "user") continue
                childMap.getOrPut(pid) { ArrayList() }.add(Pair(m.optLong("timestamp"), k))
            }
        }
        fun kids(id: String): List<String> = childMap[id]?.sortedBy { it.first }?.map { it.second } ?: emptyList()
        val chain = ArrayList<JSONObject>()
        val sibsOf = HashMap<String, List<String>>()
        var parent = ""
        val seen = HashSet<String>()
        while (true) {
            val ks = kids(parent)
            if (ks.isEmpty()) break
            val pick = choice[parent]?.takeIf { it in ks } ?: ks.firstOrNull { it in onCur } ?: ks.last()
            if (!seen.add(pick)) break
            val m = msgs.getJSONObject(pick)
            sibsOf[pick] = ks.filter { msgs.getJSONObject(it).optString("role") == m.optString("role") }
            chain.add(m)
            parent = pick
        }
        if (chain.isEmpty()) return emptyList()
        return chain.map { m ->
            val role = m.optString("role")
            if (role == "user") {
                ChatMsg(m.optString("id"), "user", m.optString("content"), emptyList(), "", null, true, parent = if (m.isNull("parentId")) "" else m.optString("parentId"), sibs = sibsOf[m.optString("id")].orEmpty())
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
                val acc = Acc()
                collect(m, acc)
                ChatMsg(m.optString("id"), "assistant", answer.toString(), steps.values.toList(), think.toString(), err, m.optBoolean("done"), acc.files.values.toList(), acc.commit)
            }
        }
    }
}
