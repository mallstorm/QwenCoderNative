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
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject

/**
 * Native chat UI on top of a hidden WebView that runs coder.qwen.ai.
 * The native layer mirrors the page's conversation and drives its input / send / stop controls.
 */
class MainActivity : Activity() {
    data class Msg(val role: String, val text: String)

    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var chatLayer: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var micBtn: ImageButton
    private lateinit var statusView: TextView
    private lateinit var banner: TextView
    private lateinit var chip: TextView
    private lateinit var progress: ProgressBar
    private lateinit var backToChat: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var listening = false
    private var pageMode = false
    private var busy = false
    private var pending: String? = null
    private var pendingBase = 0
    private var pendingAt = 0L
    private var realCount = 0
    private var shown: List<Msg> = emptyList()

    private val home = "https://coder.qwen.ai/"
    private val desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val mobileUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun dp(v: Int) = Md.dp(this, v)

    private val poll = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("qc", MODE_PRIVATE)
        window.statusBarColor = Theme.SURFACE
        window.navigationBarColor = Theme.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WebView.setWebContentsDebuggingEnabled(true)

        val root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        // ---------- chat layer ----------
        chatLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            isClickable = true
        }
        root.addView(chatLayer, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(dp(16), 0, dp(6), 0)
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "Qwen Coder"
            setTextColor(Theme.TEXT)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        statusView = TextView(this).apply {
            text = "Подключение…"
            setTextColor(Theme.MUTED)
            textSize = 12f
        }
        titleBox.addView(statusView)
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconBtn(android.R.drawable.ic_menu_add, "Новый чат") { newChat() })
        header.addView(iconBtn(android.R.drawable.ic_menu_view, "Страница Qwen") { showPage(true) })
        header.addView(iconBtn(android.R.drawable.ic_menu_more, "Меню") { showMenu() })
        chatLayer.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        banner = TextView(this).apply {
            text = "Нужен вход в Qwen. Нажмите, чтобы открыть страницу входа."
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.rgb(150, 90, 20))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { showPage(true) }
        }
        chatLayer.addView(banner, LinearLayout.LayoutParams(-1, -2))

        val body = FrameLayout(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        scroll = ScrollView(this).apply { addView(list, FrameLayout.LayoutParams(-1, -2)) }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        emptyView = TextView(this).apply {
            text = "Опишите задачу для Qwen Coder.\nРепозиторий и вход настраиваются на странице Qwen (кнопка вверху)."
            setTextColor(Theme.MUTED)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        body.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        chatLayer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        chip = TextView(this).apply {
            setTextColor(Theme.TEXT)
            textSize = 13f
            background = Md.round(Theme.SURFACE, 14, this@MainActivity)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        chatLayer.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(12)
            bottomMargin = dp(4)
        })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), dp(6), dp(8), dp(8))
            setBackgroundColor(Theme.BG)
        }
        composer.addView(iconBtn(android.R.drawable.ic_menu_upload, "Прикрепить файл") { pickFile() })
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
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(2)
            rightMargin = dp(4)
        })
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
        chatLayer.addView(composer, LinearLayout.LayoutParams(-1, -2))

        // ---------- overlays ----------
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        backToChat = TextView(this).apply {
            text = "←  Чат"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = Md.round(Color.rgb(52, 77, 111), 18, this@MainActivity)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            alpha = 0.95f
            visibility = View.GONE
            setOnClickListener { showPage(false) }
        }
        root.addView(backToChat, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(8)
            topMargin = dp(8)
        })

        setContentView(root)
        configureWebView()
        if (savedInstanceState == null) web.loadUrl(home) else web.restoreState(savedInstanceState)
    }

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        contentDescription = desc
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        web.onPause()
        super.onPause()
    }

    // ---------- WebView ----------

    private fun cfgJson(): String = JSONObject().apply {
        put("msg", prefs.getString("sel_msg", ""))
        put("input", prefs.getString("sel_input", ""))
        put("send", prefs.getString("sel_send", ""))
        put("stop", prefs.getString("sel_stop", ""))
    }.toString()

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            userAgentString = if (prefs.getBoolean("desktop", true)) desktopUA else mobileUA
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host.orEmpty().lowercase()
                if (uri.scheme != "https") {
                    if (uri.scheme == "mailto" || uri.scheme == "tel") launchExternal(uri)
                    return true
                }
                if (host == "accounts.google.com" || (host == "auth.qwen.ai" && uri.path.orEmpty().contains("oauth/google"))) {
                    showGoogleLoginNotice()
                    return true
                }
                if (host == "coder.qwen.ai" || host == "auth.qwen.ai" || host.endsWith(".qwen.ai")) return false
                launchExternal(uri)
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith("https://") == true && url.contains("qwen.ai")) {
                    view?.evaluateJavascript(
                        "(function(){var m=document.querySelector('meta[name=\"viewport\"]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.setAttribute('content','width=1280, minimum-scale=0.2, maximum-scale=3, user-scalable=yes');return true;})();",
                        null
                    )
                    Bridge.inject(this@MainActivity, web, cfgJson())
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(this@MainActivity, "Ошибка загрузки Qwen. Проверьте сеть и VPN.", Toast.LENGTH_LONG).show()
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileChooser?.onReceiveValue(null)
                fileChooser = callback
                return try {
                    startActivityForResult(params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, 1001)
                    true
                } catch (_: Exception) {
                    fileChooser = null
                    false
                }
            }
        }
    }

    private fun showPage(on: Boolean) {
        pageMode = on
        chatLayer.visibility = if (on) View.GONE else View.VISIBLE
        backToChat.visibility = if (on) View.VISIBLE else View.GONE
        hideKeyboard()
        if (on) web.requestFocus()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---------- mirroring ----------

    private fun tick() {
        if (pageMode) return
        web.evaluateJavascript("(window.__QC&&window.__QC.v===3)?window.__QC.snapshot():null") { raw ->
            val s = Bridge.unquote(raw)
            if (s == null) {
                Bridge.inject(this, web, cfgJson())
                return@evaluateJavascript
            }
            try {
                applySnapshot(JSONObject(s))
            } catch (_: Exception) {
            }
        }
    }

    private fun applySnapshot(o: JSONObject) {
        val login = o.optBoolean("login")
        val hasInput = o.optBoolean("hasInput")
        busy = o.optBoolean("busy")
        val arr = o.optJSONArray("msgs")
        val msgs = ArrayList<Msg>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                msgs.add(Msg(m.optString("r"), m.optString("t")))
            }
        }
        realCount = msgs.size
        statusView.text = when {
            login -> "Нужен вход"
            busy -> "Qwen отвечает…"
            hasInput -> "Готов"
            else -> "Загрузка страницы…"
        }
        banner.visibility = if (login) View.VISIBLE else View.GONE
        sendBtn.setImageResource(if (busy) android.R.drawable.ic_media_pause else android.R.drawable.ic_menu_send)

        val p = pending
        if (p != null) {
            val key = p.take(30)
            val matched = msgs.drop(pendingBase).any { it.role == "user" && it.text.contains(key) }
            if (matched || System.currentTimeMillis() - pendingAt > 20000) pending = null
        }
        val p2 = pending
        render(if (p2 != null) msgs + Msg("user", p2) else msgs)
    }

    private fun render(display: List<Msg>) {
        if (display == shown) return
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

    private fun makeView(m: Msg): View {
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
        Md.render(this, col, m.text)
        return col
    }

    // ---------- actions ----------

    private fun send() {
        if (busy) {
            web.evaluateJavascript("(window.__QC?window.__QC.stop():'none')", null)
            return
        }
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val b64 = Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        web.evaluateJavascript("(window.__QC?window.__QC.send('" + b64 + "'):'nobridge')") { r ->
            if (Bridge.unquote(r) == "ok") {
                input.setText("")
                chip.visibility = View.GONE
                pending = t
                pendingBase = realCount
                pendingAt = System.currentTimeMillis()
                applyPendingNow()
            } else {
                Toast.makeText(this, "Не найдено поле ввода Qwen. Откройте страницу (кнопка вверху) и войдите.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyPendingNow() {
        val p = pending ?: return
        val base = shown.filter { !(it.role == "user" && it.text == p) }
        render(base + Msg("user", p))
    }

    private fun newChat() {
        web.evaluateJavascript("(window.__QC?window.__QC.newChat():'none')") { r ->
            if (Bridge.unquote(r) != "ok") {
                Toast.makeText(this, "Кнопка нового чата не найдена, открываю главную", Toast.LENGTH_SHORT).show()
                web.loadUrl(home)
            }
            pending = null
        }
    }

    private fun pickFile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 1002)
        } catch (_: Exception) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun handlePicked(uri: Uri) {
        Thread {
            try {
                val name = queryName(uri)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 8 * 1024 * 1024) {
                    runOnUiThread { Toast.makeText(this, "Файл больше 8 МБ", Toast.LENGTH_LONG).show() }
                } else {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    runOnUiThread { uploadToPage(name, mime, b64) }
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun uploadToPage(name: String, mime: String, b64: String) {
        val id = System.currentTimeMillis().toString()
        web.evaluateJavascript("__QC.chunkStart('" + id + "')", null)
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 400000, b64.length)
            web.evaluateJavascript("__QC.chunk('" + id + "','" + b64.substring(i, end) + "')", null)
            i = end
        }
        val nameB64 = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        web.evaluateJavascript("__QC.attachFile('" + id + "','" + nameB64 + "','" + mime.replace("'", "") + "')") { r ->
            if (Bridge.unquote(r) == "ok") {
                chip.text = "📎 " + name + "   ✕"
                chip.visibility = View.VISIBLE
            } else {
                Toast.makeText(this, "Поле загрузки не найдено. Прикрепите файл на странице Qwen.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- menu ----------

    private fun showMenu() {
        val desktop = prefs.getBoolean("desktop", true)
        val choices = arrayOf(
            "Страница Qwen (вход, репозиторий)",
            "Войти по email / коду",
            "Войти через Google в Chrome",
            "Обновить страницу",
            if (desktop) "Режим страницы: десктоп (сменить на мобильный)" else "Режим страницы: мобильный (сменить на десктоп)",
            "Селекторы элементов",
            "Диагностика страницы (копировать)",
            "Выйти (очистить cookies)"
        )
        AlertDialog.Builder(this)
            .setTitle("Qwen Coder Native")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> showPage(true)
                    1 -> { web.loadUrl("https://coder.qwen.ai/auth"); showPage(true) }
                    2 -> showGoogleLoginNotice()
                    3 -> web.reload()
                    4 -> {
                        prefs.edit().putBoolean("desktop", !desktop).apply()
                        web.settings.userAgentString = if (!desktop) desktopUA else mobileUA
                        web.reload()
                    }
                    5 -> showSelectors()
                    6 -> showDump()
                    7 -> {
                        CookieManager.getInstance().removeAllCookies(null)
                        web.clearCache(true)
                        web.loadUrl(home)
                        pending = null
                    }
                }
            }.show()
    }

    private fun showSelectors() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val keys = listOf(
            "sel_msg" to "CSS: сообщения",
            "sel_input" to "CSS: поле ввода",
            "sel_send" to "CSS: кнопка отправки",
            "sel_stop" to "CSS: кнопка остановки"
        )
        val fields = keys.map { (k, label) ->
            EditText(this).apply {
                hint = label
                setSingleLine()
                setText(prefs.getString(k, ""))
            }.also { box.addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Селекторы (пусто = авто)")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val e = prefs.edit()
                keys.forEachIndexed { i, kv -> e.putString(kv.first, fields[i].text.toString().trim()) }
                e.apply()
                Bridge.inject(this, web, cfgJson())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDump() {
        web.evaluateJavascript("(window.__QC?window.__QC.dump():'Мост не подключён: страница ещё не загружена')") { r ->
            val s = Bridge.unquote(r) ?: "Нет данных"
            val tv = TextView(this).apply {
                text = s
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextIsSelectable(true)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val sv = ScrollView(this).apply { addView(tv) }
            AlertDialog.Builder(this)
                .setTitle("Структура страницы")
                .setView(sv)
                .setPositiveButton("Копировать") { _, _ -> Md.copy(this, s) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun showGoogleLoginNotice() {
        AlertDialog.Builder(this)
            .setTitle("Вход Google")
            .setMessage("Google-авторизация в Android WebView может блокироваться, а Chrome не передаёт свою сессию в WebView. Используйте вход по email с кодом подтверждения.")
            .setPositiveButton("Войти по email") { _, _ ->
                web.loadUrl("https://coder.qwen.ai/auth")
                showPage(true)
            }
            .setNeutralButton("Открыть Chrome") { _, _ -> openChrome() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openChrome() {
        try {
            val tab = CustomTabsIntent.Builder().setShowTitle(false).setUrlBarHidingEnabled(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
            tab.intent.setPackage("com.android.chrome")
            tab.launchUrl(this, Uri.parse(home))
        } catch (_: Exception) {
            launchExternal(Uri.parse(home))
        }
    }

    private fun launchExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
        }
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
            Toast.makeText(this, "Служба распознавания речи недоступна", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this@MainActivity, "Ошибка диктовки: $error", Toast.LENGTH_SHORT).show()
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

    // ---------- lifecycle plumbing ----------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDictation()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            fileChooser?.onReceiveValue(if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null)
            fileChooser = null
        } else if (requestCode == 1002 && resultCode == RESULT_OK) {
            data?.data?.let { handlePicked(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy back handling")
    override fun onBackPressed() {
        if (pageMode) {
            if (web.canGoBack()) web.goBack() else showPage(false)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        recognizer?.destroy()
        web.destroy()
        super.onDestroy()
    }
}
