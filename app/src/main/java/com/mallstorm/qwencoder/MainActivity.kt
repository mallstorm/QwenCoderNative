package com.mallstorm.qwencoder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.drawerlayout.widget.DrawerLayout
import org.json.JSONObject
import java.util.concurrent.Executors

/** Native Qwen Coder client: talks to coder.qwen.ai's REST/SSE API directly (no WebView chat). */
class MainActivity : Activity() {
    private class Live {
        val answer = StringBuilder()
        val think = StringBuilder()
        val steps = LinkedHashMap<String, Step>()
        var error: String? = null
    }

    private lateinit var api: Api
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    private lateinit var loginView: LinearLayout
    private lateinit var tokenInput: EditText
    private lateinit var loginStatus: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var taskBox: LinearLayout
    private lateinit var accountView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var micBtn: ImageButton

    private var curId: String? = null
    private var curTitle = ""
    private var curRepo = ""
    private var thread: List<ChatMsg> = emptyList()
    private var leafId: String? = null
    private var streaming = false
    private var cancelled = false
    private var running = false
    private var live: Live? = null
    private var pending: String? = null
    private var pendingBase = 0
    private var shown: List<ChatMsg> = emptyList()
    private var envs: List<Env>? = null
    private var tasks: List<TaskItem> = emptyList()
    private val expanded = HashSet<String>()
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private fun dp(v: Int) = Md.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun model(): String = prefs.getString("model", "qwen3-coder-plus") ?: "qwen3-coder-plus"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(this)
        prefs = getSharedPreferences("qc", MODE_PRIVATE)
        window.statusBarColor = Theme.SURFACE
        window.navigationBarColor = Theme.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        drawer = buildMain()
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        loginView = buildLogin()
        root.addView(loginView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (api.loggedIn) showMain() else showLogin()
    }

    // ---------- UI construction ----------

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        background = Md.round(if (filled) Theme.ACCENT else Theme.SURFACE, 22, this@MainActivity)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener { onClick() }
    }

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        contentDescription = desc
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun buildLogin(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isClickable = true
        }
        box.addView(TextView(this).apply {
            text = "Qwen Coder"
            setTextColor(Theme.TEXT)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "Вставьте значение cookie refresh_token с coder.qwen.ai.\n\n" +
                "Где взять: в Chrome на компьютере откройте coder.qwen.ai (вы уже вошли), F12 → Application → Cookies → .qwen.ai → refresh_token → скопируйте Value. " +
                "Подойдёт и строка Cookie целиком, и JSON-экспорт расширения Cookie-Editor.\n\n" +
                "Токен хранится только на этом телефоне."
            setTextColor(Theme.MUTED)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        })
        tokenInput = EditText(this).apply {
            hint = "refresh_token"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP
            background = Md.round(Theme.SURFACE, 14, this@MainActivity)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(tokenInput, LinearLayout.LayoutParams(-1, -2))
        loginStatus = TextView(this).apply {
            setTextColor(Color.rgb(240, 170, 90))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(loginStatus)
        box.addView(pill("Войти", true) { doLogin(tokenInput.text.toString()) }, LinearLayout.LayoutParams(-1, -2))
        box.addView(pill("Войти через браузер приложения (email)", false) {
            startActivityForResult(Intent(this, WebLoginActivity::class.java), 3001)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return box
    }

    private fun buildMain(): DrawerLayout {
        val dl = DrawerLayout(this)

        // ----- content -----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(iconBtn(android.R.drawable.ic_menu_agenda, "Задачи") { drawer.openDrawer(Gravity.START) })
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            text = "Qwen Coder"
            setTextColor(Theme.TEXT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = TextView(this).apply {
            setTextColor(Theme.MUTED)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleBox.addView(titleView)
        titleBox.addView(subtitleView)
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        header.addView(iconBtn(android.R.drawable.ic_menu_add, "Новая задача") { showNewTask(null) })
        header.addView(iconBtn(android.R.drawable.ic_menu_more, "Меню") { showMenu() })
        content.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        val body = FrameLayout(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        scroll = ScrollView(this).apply { addView(list, FrameLayout.LayoutParams(-1, -2)) }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        emptyView = TextView(this).apply {
            text = "Выберите задачу слева или создайте новую (+)."
            setTextColor(Theme.MUTED)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        body.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(6), dp(8), dp(8))
            setBackgroundColor(Theme.BG)
        }
        input = EditText(this).apply {
            hint = "Сообщение Qwen Coder"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 16f
            background = Md.round(Theme.SURFACE, 22, this@MainActivity)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        micBtn = iconBtn(android.R.drawable.ic_btn_speak_now, "Голосовой ввод") { toggleDictation() }
        composer.addView(micBtn)
        sendBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(Color.WHITE)
            contentDescription = "Отправить"
            background = Md.round(Theme.ACCENT, 22, this@MainActivity)
            setOnClickListener { send() }
        }
        composer.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(2) })
        content.addView(composer, LinearLayout.LayoutParams(-1, -2))
        dl.addView(content, DrawerLayout.LayoutParams(-1, -1))

        // ----- drawer -----
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            isClickable = true
        }
        side.addView(TextView(this).apply {
            text = "Задачи"
            setTextColor(Theme.TEXT)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(22), dp(18), dp(10))
        })
        side.addView(pill("+  Новая задача", true) {
            drawer.closeDrawers()
            showNewTask(null)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(8)
        })
        taskBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(ScrollView(this).apply { addView(taskBox, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        accountView = TextView(this).apply {
            setTextColor(Theme.MUTED)
            textSize = 13f
            setPadding(dp(18), dp(12), dp(18), dp(14))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Выйти из аккаунта?")
                    .setPositiveButton("Выйти") { _, _ -> logout() }
                    .setNegativeButton("Отмена", null).show()
            }
        }
        side.addView(accountView)
        dl.addView(side, DrawerLayout.LayoutParams(dp(300), -1).apply { gravity = Gravity.START })
        return dl
    }

    private fun showLogin() {
        loginView.visibility = View.VISIBLE
        drawer.visibility = View.GONE
    }

    private fun showMain() {
        loginView.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        accountView.text = (api.userName.ifEmpty { api.userEmail }).ifEmpty { "Аккаунт Qwen" } + "  ·  выйти"
        updateHeader()
        render(emptyList())
        loadTasks()
        pool.execute {
            try {
                api.me()
                ui.post { accountView.text = (api.userName.ifEmpty { api.userEmail }).ifEmpty { "Аккаунт Qwen" } + "  ·  выйти" }
            } catch (e: Exception) {
                ui.post { handleError(e) }
            }
        }
    }

    private fun logout() {
        api.cancelStream()
        api.logout()
        curId = null
        thread = emptyList()
        tasks = emptyList()
        envs = null
        showLogin()
    }

    private fun handleError(e: Exception) {
        if (e is ApiError && e.code == "auth") {
            toast("Сессия истекла: войдите заново")
            api.logout()
            showLogin()
        } else {
            toast(e.message ?: "Ошибка сети")
        }
    }

    // ---------- login ----------

    private fun doLogin(raw: String) {
        val (a, r) = TokenParser.extract(raw)
        if (a == null && r == null) {
            loginStatus.text = "В тексте не найден токен (ожидается строка, начинающаяся с eyJ…)"
            return
        }
        loginStatus.text = "Проверяю вход…"
        pool.execute {
            try {
                api.setTokens(a, r)
                api.ensureAccess(!r.isNullOrEmpty())
                api.me()
                ui.post {
                    loginStatus.text = ""
                    tokenInput.setText("")
                    showMain()
                }
            } catch (e: Exception) {
                api.logout()
                ui.post { loginStatus.text = "Не удалось войти: " + (e.message ?: "ошибка") }
            }
        }
    }

    // ---------- tasks ----------

    private fun statusLabel(s: String) = when (s) {
        "running" -> "выполняется"
        "finished" -> "готово"
        "" -> ""
        else -> s
    }

    private fun loadTasks() {
        pool.execute {
            try {
                val t = api.tasks(1)
                ui.post {
                    tasks = t
                    renderTasks()
                }
            } catch (e: Exception) {
                ui.post { handleError(e) }
            }
        }
    }

    private fun renderTasks() {
        taskBox.removeAllViews()
        if (tasks.isEmpty()) {
            taskBox.addView(TextView(this).apply {
                text = "Пока нет задач"
                setTextColor(Theme.MUTED)
                setPadding(dp(18), dp(12), dp(18), dp(12))
            })
            return
        }
        for (t in tasks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
                if (t.id == curId) setBackgroundColor(Theme.SURFACE)
                setOnClickListener {
                    drawer.closeDrawers()
                    openTask(t.id, t.title, t.repo, t.branch)
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Удалить задачу?").setMessage(t.title)
                        .setPositiveButton("Удалить") { _, _ -> deleteTask(t.id) }
                        .setNegativeButton("Отмена", null).show()
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = t.title.ifEmpty { "Без названия" }
                setTextColor(Theme.TEXT)
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val sub = listOf(t.repo, t.branch, statusLabel(t.status)).filter { it.isNotEmpty() }.joinToString(" · ")
            row.addView(TextView(this).apply {
                text = sub
                setTextColor(Theme.MUTED)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            taskBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun deleteTask(id: String) {
        pool.execute {
            try {
                api.deleteTask(id)
                ui.post {
                    if (curId == id) {
                        curId = null
                        thread = emptyList()
                        updateHeader()
                        render(emptyList())
                    }
                    loadTasks()
                }
            } catch (e: Exception) {
                ui.post { handleError(e) }
            }
        }
    }

    private fun resetChat(id: String?, title: String, repo: String, branch: String) {
        curId = id
        curTitle = title
        curRepo = listOf(repo, branch).filter { it.isNotEmpty() }.joinToString(" · ")
        thread = emptyList()
        leafId = null
        live = null
        pending = null
        streaming = false
        running = false
        cancelled = false
        expanded.clear()
        updateSend()
        updateHeader()
        render(emptyList())
        renderTasks()
    }

    private fun openTask(id: String, title: String, repo: String, branch: String) {
        resetChat(id, title, repo, branch)
        fetchDetail(id)
    }

    private fun updateHeader() {
        titleView.text = curTitle.ifEmpty { "Qwen Coder" }
        val state = if (streaming || running) "работает…" else ""
        subtitleView.text = listOf(curRepo, state).filter { it.isNotEmpty() }.joinToString(" · ")
        subtitleView.visibility = if (subtitleView.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateSend() {
        sendBtn.setImageResource(if (streaming) android.R.drawable.ic_media_pause else android.R.drawable.ic_menu_send)
    }

    // ---------- detail / polling ----------

    private fun fetchDetail(id: String) {
        pool.execute {
            try {
                val st = api.taskStatus(id)
                val d = api.taskDetail(id)
                ui.post {
                    if (curId == id) {
                        running = st == "running"
                        applyDetail(d)
                        if (running) startPolling()
                    }
                }
            } catch (e: Exception) {
                ui.post { handleError(e) }
            }
        }
    }

    private fun applyDetail(d: JSONObject) {
        thread = api.parseThread(d)
        leafId = thread.lastOrNull()?.id
        val tail = thread.lastOrNull()
        if (tail != null && tail.role == "assistant" && tail.done) running = false
        val title = d.optString("title")
        if (title.isNotEmpty() && title != "null") curTitle = title
        val info = d.optJSONObject("meta")?.optJSONObject("repo_info")
        if (info != null) {
            val repo = info.optString("repository")
            val br = info.optString("selected_branch")
            curRepo = listOf(repo, br).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        updateHeader()
        rebuildDisplay()
    }

    private val poll = object : Runnable {
        override fun run() {
            val id = curId ?: return
            if (!(streaming || running)) return
            pool.execute {
                try {
                    val st = api.taskStatus(id)
                    val d = api.taskDetail(id)
                    ui.post {
                        if (curId == id) {
                            val was = running
                            running = st == "running"
                            applyDetail(d)
                            if (was && !running) loadTasks()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            ui.postDelayed(this, 2500)
        }
    }

    private fun startPolling() {
        ui.removeCallbacks(poll)
        ui.postDelayed(poll, 1500)
    }

    private val renderTick = Runnable { rebuildDisplay() }
    private fun scheduleRender() {
        ui.removeCallbacks(renderTick)
        ui.postDelayed(renderTick, 90)
    }

    private fun rebuildDisplay() {
        val base = ArrayList(thread)
        val p = pending
        if (p != null) {
            val users = base.filter { it.role == "user" }
            if (users.size > pendingBase && users.drop(pendingBase).any { it.text.trim() == p.trim() }) pending = null
        }
        val p2 = pending
        if (p2 != null) base.add(ChatMsg("pending", "user", p2, emptyList(), "", null, true))
        val lv = live
        if (streaming && lv != null) {
            val lm = ChatMsg("live", "assistant", lv.answer.toString(), lv.steps.values.toList(), lv.think.toString(), lv.error, false)
            val last = base.lastOrNull()
            if (last == null || last.role == "user") {
                base.add(lm)
            } else if (!(last.text.length >= lm.text.length && last.steps.size >= lm.steps.size)) {
                base[base.size - 1] = lm.copy(id = last.id)
            }
        } else if (running && base.lastOrNull()?.role == "user") {
            base.add(ChatMsg("wait", "assistant", "", emptyList(), "", null, false))
        }
        render(base)
    }

    // ---------- rendering ----------

    private fun render(display: List<ChatMsg>) {
        if (display == shown) {
            emptyView.visibility = if (display.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        val child = scroll.getChildAt(0)
        val atBottom = child == null || child.height - (scroll.height + scroll.scrollY) < dp(160)
        for (i in display.indices) {
            if (i < shown.size && shown[i] == display[i]) continue
            val v = makeView(display[i])
            if (i < list.childCount) list.removeViewAt(i)
            list.addView(v, i)
        }
        while (list.childCount > display.size) list.removeViewAt(list.childCount - 1)
        shown = display
        emptyView.visibility = if (display.isEmpty()) View.VISIBLE else View.GONE
        if (atBottom) scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }
    }

    private fun makeView(m: ChatMsg): View {
        if (m.role == "user") {
            val wrap = LinearLayout(this).apply {
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            }
            wrap.addView(TextView(this).apply {
                text = m.text
                setTextColor(Theme.TEXT)
                textSize = 15.5f
                setTextIsSelectable(true)
                background = Md.round(Theme.USER_BUBBLE, 18, this@MainActivity)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxWidth = (resources.displayMetrics.widthPixels * 0.85).toInt()
            })
            return wrap
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        }
        if (m.steps.isNotEmpty()) col.addView(stepsView(m))
        if (m.text.isNotBlank()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            Md.render(this, box, m.text)
            col.addView(box)
        } else if (m.error == null) {
            col.addView(TextView(this).apply {
                text = if (m.done) "(пустой ответ)" else "Qwen работает…"
                setTextColor(Theme.MUTED)
                textSize = 14f
            })
        }
        if (m.error != null) {
            col.addView(TextView(this).apply {
                text = "Ошибка: " + m.error
                setTextColor(Color.rgb(240, 110, 110))
                textSize = 14f
                setPadding(0, dp(4), 0, 0)
            })
        }
        return col
    }

    private fun stepsView(m: ChatMsg): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Md.round(Theme.SURFACE, 12, this@MainActivity)
            setPadding(dp(12), dp(10), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = "Действия: " + m.steps.size
            setTextColor(Theme.MUTED)
            textSize = 12f
            setPadding(0, 0, 0, dp(4))
        })
        for (s in m.steps) {
            val ok = s.status == "finished" || s.status == "done" || s.status == "success"
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val logView = TextView(this).apply {
                text = if (s.log.length > 6000) s.log.take(6000) + "\n…" else s.log.ifEmpty { "(нет вывода)" }
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Color.rgb(190, 200, 215))
                setTextIsSelectable(true)
                setPadding(dp(4), dp(2), dp(4), dp(6))
                visibility = if (expanded.contains(s.id)) View.VISIBLE else View.GONE
            }
            val title = TextView(this).apply {
                text = (if (ok) "✓ " else "● ") + s.type.ifEmpty { "Шаг" } + " · " + s.action.replace("\n", " ").take(110)
                setTextColor(if (ok) Color.rgb(130, 200, 150) else Color.rgb(240, 180, 90))
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    if (logView.visibility == View.VISIBLE) {
                        logView.visibility = View.GONE
                        expanded.remove(s.id)
                    } else {
                        logView.visibility = View.VISIBLE
                        expanded.add(s.id)
                    }
                }
            }
            row.addView(title)
            row.addView(logView)
            card.addView(row)
        }
        return card
    }

    // ---------- sending ----------

    private fun send() {
        if (streaming) {
            cancelled = true
            api.cancelStream()
            toast("Остановлено на телефоне. Задача может продолжить работу на сервере.")
            return
        }
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val id = curId
        if (id == null) {
            showNewTask(t)
            return
        }
        input.setText("")
        startStream(id, t)
    }

    private fun startStream(id: String, text: String) {
        streaming = true
        cancelled = false
        live = Live()
        pending = text
        pendingBase = thread.count { it.role == "user" }
        updateSend()
        updateHeader()
        rebuildDisplay()
        startPolling()
        val parent = leafId
        val mdl = model()
        pool.execute {
            var err: String? = null
            try {
                api.stream(id, text, parent, mdl) { data -> ui.post { applyLive(id, data) } }
            } catch (e: Exception) {
                if (!cancelled) {
                    err = e.message ?: e.javaClass.simpleName
                    if (e is ApiError && e.code == "auth") ui.post { handleError(e) }
                }
            }
            val errMsg: String? = err
            ui.post {
                if (curId == id) {
                    streaming = false
                    updateSend()
                    if (errMsg != null) toast(errMsg)
                    fetchDetail(id)
                    loadTasks()
                }
            }
        }
    }

    private fun applyLive(id: String, data: String) {
        if (curId != id) return
        val lv = live ?: return
        try {
            val j = JSONObject(data)
            j.optJSONObject("error")?.let { e -> lv.error = e.optString("details").ifEmpty { e.optString("code") } }
            val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
            if (delta != null) {
                val phase = delta.optString("phase").ifEmpty { "answer" }
                val content = delta.optString("content")
                when {
                    phase == "answer" -> lv.answer.append(content)
                    phase.startsWith("think") -> lv.think.append(content)
                    else -> api.mergeSteps(lv.steps, delta.optJSONObject("extra")?.optJSONArray("log_list"))
                }
            }
        } catch (_: Exception) {
        }
        scheduleRender()
    }

    // ---------- new task ----------

    private fun showNewTask(text: String?) {
        toast("Загружаю репозитории…")
        pool.execute {
            try {
                val e = envs ?: api.environments().also { envs = it }
                ui.post { newTaskDialog(e, text) }
            } catch (ex: Exception) {
                ui.post { handleError(ex) }
            }
        }
    }

    private fun newTaskDialog(envList: List<Env>, text: String?) {
        val names = listOf("Без репозитория") + envList.map { it.repo.ifEmpty { it.name } }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val envSp = Spinner(this)
        envSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val brSp = Spinner(this)
        val modelEt = EditText(this).apply {
            hint = "Модель"
            setSingleLine()
            setText(model())
        }
        fun fill(pos: Int) {
            val bs: List<String> = if (pos == 0) emptyList<String>() else envList[pos - 1].branches.sortedBy { if (it == "main" || it == "master") 0 else 1 }
            brSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bs)
            brSp.isEnabled = bs.isNotEmpty()
        }
        envSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = fill(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        box.addView(TextView(this).apply { this.text = "Репозиторий" })
        box.addView(envSp)
        box.addView(TextView(this).apply { this.text = "Ветка" })
        box.addView(brSp)
        box.addView(modelEt)
        val last = prefs.getInt("last_env", 0)
        if (last in names.indices) envSp.setSelection(last)
        fill(envSp.selectedItemPosition)

        AlertDialog.Builder(this)
            .setTitle("Новая задача")
            .setView(box)
            .setPositiveButton("Создать") { _, _ ->
                val pos = envSp.selectedItemPosition
                val env = if (pos == 0) null else envList[pos - 1]
                val branch = (brSp.selectedItem as? String).orEmpty()
                val mdl = modelEt.text.toString().trim().ifEmpty { "qwen3-coder-plus" }
                prefs.edit().putString("model", mdl).putInt("last_env", pos).apply()
                pool.execute {
                    try {
                        val id = api.newTask(env, branch, mdl)
                        ui.post {
                            resetChat(id, "Новый чат", env?.repo ?: "", branch)
                            if (text != null) {
                                input.setText("")
                                startStream(id, text)
                            }
                            loadTasks()
                        }
                    } catch (e: Exception) {
                        ui.post { handleError(e) }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- menu ----------

    private fun openUrl(url: String) {
        try {
            val tab = CustomTabsIntent.Builder().build()
            tab.intent.setPackage("com.android.chrome")
            tab.launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                toast("Нет приложения для открытия ссылки")
            }
        }
    }

    private fun showMenu() {
        val id = curId
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (id != null) {
            items.add("Открыть превью проекта" to { openUrl("https://$id.preview.qwenlm.io/") })
            items.add("Открыть задачу на сайте" to { openUrl("https://coder.qwen.ai/c/$id") })
        }
        items.add("Репозитории и GitHub (сайт)" to { openUrl("https://coder.qwen.ai/") })
        items.add("Модель: " + model() to { changeModel() })
        items.add("Обновить" to {
            envs = null
            loadTasks()
            if (id != null) fetchDetail(id)
        })
        items.add("Выйти" to { logout() })
        AlertDialog.Builder(this)
            .setTitle("Qwen Coder")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun changeModel() {
        val et = EditText(this).apply {
            setSingleLine()
            setText(model())
        }
        AlertDialog.Builder(this).setTitle("ID модели").setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) prefs.edit().putString("model", v).apply()
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------- dictation ----------

    private fun toggleDictation() {
        if (listening) recognizer?.stopListening() else startDictation()
    }

    private fun startDictation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Служба распознавания речи недоступна")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    finishDictation()
                    toast("Ошибка диктовки: $error")
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        input.setText((input.text.toString().trimEnd() + " " + text).trim())
                        input.setSelection(input.text.length)
                    }
                    finishDictation()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        listening = true
        micBtn.setColorFilter(Color.RED)
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }

    private fun finishDictation() {
        listening = false
        micBtn.setColorFilter(Color.WHITE)
        recognizer?.destroy()
        recognizer = null
    }

    // ---------- lifecycle ----------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDictation()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001 && resultCode == RESULT_OK) {
            val rt = data?.getStringExtra("refresh")
            if (!rt.isNullOrEmpty()) doLogin(rt)
        }
    }

    override fun onResume() {
        super.onResume()
        if ((streaming || running) && curId != null) startPolling()
    }

    @Deprecated("Legacy back handling")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawers() else super.onBackPressed()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        api.cancelStream()
        recognizer?.destroy()
        pool.shutdownNow()
        super.onDestroy()
    }
}
