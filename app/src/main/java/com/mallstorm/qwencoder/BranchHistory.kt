package com.mallstorm.qwencoder

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

/**
 * Public GitHub REST history for the branch picker. Never sends Qwen credentials
 * to GitHub. Private repositories use the metadata supplied by Qwen instead.
 */
data class BranchHistory(
    val updatedAt: Long = 0L,
    val firstUniqueAt: Long = 0L,
    val ahead: Int? = null,
    val behind: Int? = null,
    val category: String = "",
    val summary: String = "",
    val error: String = "",
    val checkedAt: Long = System.currentTimeMillis()
)

object BranchHistoryService {
    private const val API = "https://api.github.com/repos/"

    fun timestamp(value: String): Long {
        if (value.isBlank() || value == "null") return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            catch (_: Exception) { 0L }
        }
    }

    fun formatted(time: Long): String {
        if (time <= 0L) return ""
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")).format(Date(time))
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun repository(raw: String): String? {
        val clean = raw.trim().removePrefix("https://github.com/").removePrefix("http://github.com/")
            .removeSuffix(".git").trim('/')
        val parts = clean.split('/')
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts.joinToString("/") else null
    }

    private fun request(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "QwenCoderNative-BranchHistory")
        try {
            return if (c.responseCode in 200..299) {
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                throw IllegalStateException(when (c.responseCode) {
                    401, 404 -> "История недоступна без доступа к GitHub"
                    403, 429 -> "Лимит GitHub API; обновите позже"
                    else -> "GitHub API: HTTP " + c.responseCode
                })
            }
        } finally {
            c.disconnect()
        }
    }

    fun inspect(fullName: String, branch: String, base: String): BranchHistory {
        val repo = repository(fullName)
            ?: return BranchHistory(error = "Неизвестен адрес owner/repo")
        if (branch.isBlank()) return BranchHistory(error = "Пустое имя ветки")
        val path = repo.split('/').joinToString("/") { encoded(it) }
        val root = API + path
        return try {
            val recent = JSONArray(request(root + "/commits?sha=" + encoded(branch) + "&per_page=5"))
            val latest = recent.optJSONObject(0)
            val updated = commitDate(latest)
            if (branch == base) {
                return BranchHistory(updatedAt = updated, category = "Основная ветка",
                    summary = "Базовая ветка репозитория")
            }
            val comparison = try {
                JSONObject(request(root + "/compare/" + encoded(base) + "..." + encoded(branch)))
            } catch (ex: Exception) {
                return BranchHistory(updatedAt = updated,
                    category = "История коммитов",
                    summary = "Сравнение с " + base + " недоступно",
                    error = ex.message.orEmpty())
            }
            val ahead = comparison.optInt("ahead_by", 0)
            val behind = comparison.optInt("behind_by", 0)
            val changes = comparison.optJSONArray("commits") ?: JSONArray()
            val files = comparison.optJSONArray("files") ?: JSONArray()
            val earliest = if (ahead > 0 && changes.length() > 0) commitDate(changes.optJSONObject(0)) else 0L
            val messages = (0 until minOf(changes.length(), 15)).mapNotNull { i ->
                changes.optJSONObject(i)?.optJSONObject("commit")?.optString("message")
                    ?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            }
            if (ahead == 0) {
                BranchHistory(updatedAt = updated, ahead = ahead, behind = behind,
                    category = if (behind > 0) "Отстаёт от " + base else "Без отличий",
                    summary = if (behind > 0) "Собственных коммитов нет" else "Совпадает с " + base)
            } else {
                val report = summarize(messages, files)
                BranchHistory(updatedAt = updated, firstUniqueAt = earliest,
                    ahead = ahead, behind = behind, category = report.first, summary = report.second)
            }
        } catch (ex: Exception) {
            BranchHistory(error = ex.message ?: "История GitHub недоступна")
        }
    }

    private fun commitDate(value: JSONObject?): Long {
        val c = value?.optJSONObject("commit") ?: return 0L
        val date = c.optJSONObject("committer")?.optString("date")
            .orEmpty().ifBlank { c.optJSONObject("author")?.optString("date").orEmpty() }
        return timestamp(date)
    }

    /** Keyword-based, Russian-language summary of actual branch-only commits/files. */
    private fun summarize(messages: List<String>, files: JSONArray): Pair<String, String> {
        val paths = (0 until files.length()).mapNotNull { files.optJSONObject(it)?.optString("filename") }
        val subjects = messages.joinToString(" ").lowercase(Locale.ROOT)
        val names = paths.joinToString(" ").lowercase(Locale.ROOT)
        val combined = subjects + " " + names
        val categories = LinkedHashSet<String>()
        if (paths.any {
                val p = it.lowercase(Locale.ROOT)
                p.contains("ui/") || p.contains("view") || p.contains("activity") ||
                    p.contains("/res/") || p.contains("screen") || p.contains("component") ||
                    p.endsWith(".css") || p.endsWith(".tsx") || p.endsWith(".jsx")
            } || Regex("\\b(ui|layout|design|interface|интерфейс)\\b").containsMatchIn(subjects)) categories.add("Интерфейс")
        if (paths.any {
                val p = it.lowercase(Locale.ROOT)
                p.contains("api") || p.contains("server") || p.contains("backend") ||
                    p.contains("service") || p.contains("database") || p.contains("repository")
            } || Regex("\\b(backend|endpoint|api|сервер)\\b").containsMatchIn(subjects)) categories.add("Backend")
        if (paths.any {
                val p = it.lowercase(Locale.ROOT)
                p.contains("workflow") || p.contains("gradle") || p.contains("docker") ||
                    p.contains("cmakelists") || p.contains("pipeline")
            }) categories.add("Сборка/CI")
        if (paths.any { it.lowercase(Locale.ROOT).contains("test") }) categories.add("Тесты")
        if (paths.any { it.lowercase(Locale.ROOT).endsWith(".md") }) categories.add("Документация")
        val category = categories.take(2).joinToString(" + ").ifBlank { "Код" }

        val topics = LinkedHashSet<String>()
        val rules = listOf(
            "авторизация" to "auth|oauth|login|sign.in|token|авторизац|вход",
            "чат" to "chat|message|prompt|stream|чат|сообщени",
            "ветки Git" to "branch|merge|compare|ветк",
            "репозитории" to "repository|repositories|repo|git(hub)?|репозитор",
            "модели ИИ" to "model|qwen|llm|модел",
            "интерфейс" to "ui|layout|screen|theme|design|интерфейс|тем[аы]",
            "файлы" to "file|folder|upload|download|файл",
            "превью" to "preview|render|превью",
            "поиск" to "search|filter|поиск",
            "уведомления" to "notif|alert|уведомлен",
            "настройки" to "setting|config|настройк",
            "сборка" to "build|gradle|release|apk|workflow|сборк",
            "тесты" to "test|spec|тест"
        )
        for ((topic, pattern) in rules) {
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(combined)) topics.add(topic)
        }
        val action = when {
            Regex("\\b(fix|fixed|bug|repair|исправ|почин)\\w*").containsMatchIn(subjects) -> "Исправления"
            Regex("\\b(add|added|feat|implement|new|добав|реализ)\\w*").containsMatchIn(subjects) -> "Новые функции"
            else -> "Изменения"
        }
        val detail = if (topics.isNotEmpty()) topics.take(3).joinToString(", ")
            else if (files.length() > 0) "затронуто файлов: " + files.length()
            else "коммиты ветки"
        return category to (action + ": " + detail)
    }
}
