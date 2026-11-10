package com.mallstorm.qwencoder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.drawerlayout.widget.DrawerLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Native Qwen Coder client: talks to coder.qwen.ai's REST/SSE API directly (no WebView chat). */
class MainActivity : Activity() {
    private class Live {
        val answer = StringBuilder()
        val think = StringBuilder()
        val steps = LinkedHashMap<String, Step>()
        var error: String? = null
    }

    private data class ModelInfo(val id: String, val title: String, val hint: String)

    private val models = listOf(
        ModelInfo("qwen3-coder-plus", "Qwen3-Coder", "Основная модель для кода"),
        ModelInfo("qwen3.8-max", "Qwen 3.8 Max", "Самая сильная"),
        ModelInfo("qwen3.8-flash", "Qwen 3.8 Flash", "Быстрая и лёгкая")
    )

    private lateinit var api: Api
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    private lateinit var loginView: View
    private lateinit var tokenInput: EditText
    private lateinit var loginStatus: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var taskBox: LinearLayout
    private lateinit var nameView: TextView
    private lateinit var emailView: TextView
    private lateinit var avatarView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var statusDot: View
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView
    private lateinit var chipsBox: LinearLayout
    private lateinit var fab: ImageButton
    private lateinit var repoChip: TextView
    private lateinit var modelChip: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var micBtn: ImageButton
    private lateinit var codePanel: CodePanel
    private lateinit var codeBadge: TextView
    private lateinit var thinkChip: TextView
    private lateinit var searchInput: EditText
    private lateinit var runChip: TextView
    private lateinit var usageView: TextView
    private var thinking = false
    private var onlyRunning = false
    private var query = ""
    private var pinnedTasks: List<TaskItem> = emptyList()
    private var sandboxFiles: LinkedHashMap<String, ByteArray>? = null
    private var fetchWaiting = false
    private var previewWaiting = false

    private var statusAnim: ObjectAnimator? = null
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
    private var selEnv: Env? = null
    private var selBranch = ""
    private var selModel = "qwen3-coder-plus"
    private var refreshEnvsOnResume = false
    private val openSteps = HashSet<String>()
    private val cardToggled = HashSet<String>()
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private fun dp(v: Int) = Md.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    override fun attachBaseContext(base: Context) {
        val sc = base.getSharedPreferences("qc", MODE_PRIVATE).getInt("fs", 100) / 100f
        val cfg = android.content.res.Configuration(base.resources.configuration)
        cfg.fontScale = cfg.fontScale * sc
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    private fun dlgTheme() = if (Theme.light) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("qc", MODE_PRIVATE)
        Theme.apply(prefs.getString("theme", "dark") ?: "dark")
        setTheme(dlgTheme())
        super.onCreate(savedInstanceState)
        api = Api(this)
        selModel = prefs.getString("model", "qwen3-coder-plus") ?: "qwen3-coder-plus"
        thinking = prefs.getBoolean("think", false)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        if (Theme.light) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        drawer = buildMain()
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        loginView = buildLogin()
        root.addView(loginView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (api.loggedIn) showMain() else showLogin()
        handleIntent(intent)
        if (intent?.getBooleanExtra("open_settings", false) == true) {
            intent.removeExtra("open_settings")
            ui.post { openSettings() }
        }
    }

    // ---------- UI helpers ----------

    private fun icon(res: Int, desc: String, tint: Int = Theme.TEXT, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        contentDescription = desc
        background = Md.rippleCircle()
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        layoutParams = lp(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun gradientButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = Md.gradient(this@MainActivity, Theme.ACCENT, Theme.ACCENT2, 16)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        setOnClickListener { onClick() }
    }

    private fun outlineButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Theme.TEXT)
        textSize = 14f
        gravity = Gravity.CENTER
        background = Md.round(Theme.SURFACE, 16, this@MainActivity, Theme.STROKE)
        setPadding(dp(18), dp(13), dp(18), dp(13))
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, iconRes: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Theme.TEXT)
        textSize = 12.5f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = dp(6)
        setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(Theme.MUTED)
        background = Md.round(Theme.SURFACE, 16, this@MainActivity, Theme.STROKE)
        setPadding(dp(10), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Theme.MUTED)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(14), dp(18), dp(6))
    }

    // ---------- UI construction ----------

    private fun buildLogin(): View {
        val sv = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            isClickable = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(28))
        }
        box.addView(Md.logoView(this, 84), lp(dp(84), dp(84)))
        box.addView(TextView(this).apply {
            text = "Qwen Coder"
            setTextColor(Theme.TEXT)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = "Нативный клиент для coder.qwen.ai"
            setTextColor(Theme.MUTED)
            textSize = 14f
            setPadding(0, 0, 0, dp(24))
        })
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Md.round(Theme.SURFACE, 16, this@MainActivity, Theme.STROKE)
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        val items = listOf(
            "Откройте coder.qwen.ai в браузере на компьютере и войдите в аккаунт",
            "F12 → Приложение → Файлы cookie → refresh_token",
            "Скопируйте значение (Value) и вставьте ниже. Подойдёт и вся строка Cookie"
        )
        items.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = (i + 1).toString()
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = Md.circleGradient(Theme.ACCENT, Theme.ACCENT2)
            }, lp(dp(22), dp(22)))
            row.addView(TextView(this).apply {
                text = s
                setTextColor(Theme.TEXT)
                textSize = 13.5f
                setPadding(dp(12), 0, 0, 0)
            }, lp(0, -2, 1f))
            steps.addView(row)
        }
        box.addView(steps, lp(-1, -2))
        tokenInput = EditText(this).apply {
            hint = "Вставьте refresh_token"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            background = Md.round(Theme.SURFACE, 14, this@MainActivity, Theme.STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(tokenInput, lp(-1, -2).apply { topMargin = dp(16) })
        loginStatus = TextView(this).apply {
            setTextColor(Theme.AMBER)
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        }
        box.addView(loginStatus, lp(-1, -2))
        box.addView(gradientButton("Войти") { doLogin(tokenInput.text.toString()) }, lp(-1, -2))
        box.addView(outlineButton("Войти через встроенный браузер (email)") {
            startActivityForResult(Intent(this, WebLoginActivity::class.java), 3001)
        }, lp(-1, -2).apply { topMargin = dp(10) })
        box.addView(TextView(this).apply {
            text = "Токен хранится только на этом телефоне."
            setTextColor(Theme.MUTED)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }, lp(-1, -2))
        sv.addView(box, FrameLayout.LayoutParams(-1, -2))
        return sv
    }

    private fun buildMain(): DrawerLayout {
        val dl = DrawerLayout(this)
        dl.setScrimColor(Color.argb(150, 0, 0, 0))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(icon(R.drawable.ic_menu, "Задачи") { drawer.openDrawer(Gravity.START) })
        header.addView(Md.logoView(this, 30), lp(dp(30), dp(30)).apply { leftMargin = dp(2) })
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            setTextColor(Theme.TEXT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        subRow.addView(statusDot, lp(dp(7), dp(7)).apply { rightMargin = dp(6) })
        subtitleView = TextView(this).apply {
            setTextColor(Theme.MUTED)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subRow.addView(subtitleView)
        titleBox.addView(titleView)
        titleBox.addView(subRow)
        header.addView(titleBox, lp(0, -2, 1f).apply { leftMargin = dp(10) })
        val codeWrap = FrameLayout(this)
        codeWrap.addView(icon(R.drawable.ic_code, "Код и изменения") { openCode() }, FrameLayout.LayoutParams(dp(48), dp(48)))
        codeBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = Md.circleGradient(Theme.ACCENT, Theme.ACCENT2)
            visibility = View.GONE
        }
        codeWrap.addView(codeBadge, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(3) })
        header.addView(codeWrap, lp(dp(48), dp(48)))
        header.addView(icon(R.drawable.ic_add, "Новая задача") { startNewTask() })
        header.addView(icon(R.drawable.ic_more, "Меню") { showMenu() })
        content.addView(header, lp(-1, dp(56)))
        content.addView(View(this).apply { setBackgroundColor(Theme.STROKE) }, lp(-1, 1))

        // body
        val body = FrameLayout(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        emptyBox = buildEmpty()
        body.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))
        fab = ImageButton(this).apply {
            setImageResource(R.drawable.ic_down)
            setColorFilter(Theme.TEXT)
            background = Md.circle(Theme.SURFACE2)
            elevation = dp(4).toFloat()
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            setOnClickListener { scroll.post { scroll.smoothScrollTo(0, list.height) } }
        }
        body.addView(fab, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(10)
        })
        scroll.viewTreeObserver.addOnScrollChangedListener { updateFab() }
        content.addView(body, lp(-1, 0, 1f))

        // chips: repository + model
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        repoChip = chip("Без репозитория", R.drawable.ic_repo) { openRepoPicker() }
        modelChip = chip("Qwen3-Coder", R.drawable.ic_sparkle) { openModelPicker() }
        thinkChip = chip("Думать: выкл", R.drawable.ic_sparkle) {
            thinking = !thinking
            prefs.edit().putBoolean("think", thinking).apply()
            updateChips()
        }
        chipRow.addView(repoChip, lp(0, -2, 1f).apply { rightMargin = dp(8) })
        chipRow.addView(thinkChip, lp(-2, -2).apply { rightMargin = dp(8) })
        chipRow.addView(modelChip, lp(-2, -2))
        content.addView(chipRow, lp(-1, -2))

        // composer
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = Md.round(Theme.SURFACE, 26, this@MainActivity, Theme.STROKE)
            setPadding(dp(18), dp(4), dp(5), dp(5))
        }
        input = EditText(this).apply {
            hint = "Опишите задачу…"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(9), 0, dp(9))
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        composer.addView(input, lp(0, -2, 1f))
        micBtn = icon(R.drawable.ic_mic, "Голосовой ввод", Theme.MUTED) { toggleDictation() }
        composer.addView(micBtn, lp(dp(40), dp(40)))
        sendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(Color.WHITE)
            contentDescription = "Отправить"
            background = Md.circleGradient(Theme.ACCENT, Theme.ACCENT2)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { send() }
        }
        composer.addView(sendBtn, lp(dp(40), dp(40)).apply { leftMargin = dp(2) })
        wrap.addView(composer, lp(-1, -2))
        content.addView(wrap, lp(-1, -2))
        dl.addView(content, DrawerLayout.LayoutParams(-1, -1))

        // drawer
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.SURFACE)
            isClickable = true
        }
        val sideHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(14))
        }
        sideHead.addView(Md.logoView(this, 36), lp(dp(36), dp(36)))
        val sideTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sideTitle.addView(TextView(this).apply {
            text = "Qwen Coder"
            setTextColor(Theme.TEXT)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        sideTitle.addView(TextView(this).apply {
            text = "Native"
            setTextColor(Theme.MUTED)
            textSize = 12f
        })
        sideHead.addView(sideTitle, lp(0, -2, 1f).apply { leftMargin = dp(12) })
        side.addView(sideHead, lp(-1, -2))
        side.addView(gradientButton("+  Новая задача") {
            drawer.closeDrawers()
            startNewTask()
        }, lp(-1, -2).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(4)
        })
        val sr = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(4))
        }
        searchInput = EditText(this).apply {
            hint = "Поиск задач"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 14f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            background = Md.round(Theme.SURFACE2, 14, this@MainActivity)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    renderTasks()
                }
            })
        }
        runChip = TextView(this).apply {
            text = "Идут"
            textSize = 12.5f
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setOnClickListener {
                onlyRunning = !onlyRunning
                styleRunChip()
                renderTasks()
            }
        }
        styleRunChip()
        sr.addView(searchInput, lp(0, -2, 1f))
        sr.addView(runChip, lp(-2, -2).apply { leftMargin = dp(8) })
        side.addView(sr, lp(-1, -2))
        taskBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(ScrollView(this).apply { addView(taskBox, FrameLayout.LayoutParams(-1, -2)) }, lp(-1, 0, 1f))
        side.addView(View(this).apply { setBackgroundColor(Theme.STROKE) }, lp(-1, 1))
        usageView = TextView(this).apply {
            setTextColor(Theme.MUTED)
            textSize = 12f
            setPadding(dp(18), dp(10), dp(18), 0)
            visibility = View.GONE
        }
        side.addView(usageView, lp(-1, -2))
        val acc = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Md.rippleRect(null)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Выйти из аккаунта?")
                    .setPositiveButton("Выйти") { _, _ -> logout() }
                    .setNegativeButton("Отмена", null).show()
            }
        }
        avatarView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = Md.circleGradient(Theme.ACCENT, Theme.ACCENT2)
        }
        acc.addView(avatarView, lp(dp(38), dp(38)))
        val accText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameView = TextView(this).apply {
            setTextColor(Theme.TEXT)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        emailView = TextView(this).apply {
            setTextColor(Theme.MUTED)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        accText.addView(nameView)
        accText.addView(emailView)
        acc.addView(accText, lp(0, -2, 1f).apply { leftMargin = dp(12) })
        acc.addView(TextView(this).apply {
            text = "выйти"
            setTextColor(Theme.MUTED)
            textSize = 12f
        })
        side.addView(acc, lp(-1, -2))
        dl.addView(side, DrawerLayout.LayoutParams(dp(310), -1).apply { gravity = Gravity.START })
        codePanel = CodePanel(this, { api.changesFromSteps(it) }, { dl.closeDrawer(Gravity.END) }, { openUrl(it) }, { fetchSandbox() }, { startPreviewServer() }, { saveSandbox() })
        val pw = minOf((resources.displayMetrics.widthPixels * 0.94f).toInt(), dp(520))
        dl.addView(codePanel, DrawerLayout.LayoutParams(pw, -1).apply { gravity = Gravity.END })
        dl.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.END)
        dl.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(v: View) {
                if (v === codePanel) {
                    dl.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN, Gravity.END)
                    codePanel.setActive(true)
                }
            }
            override fun onDrawerClosed(v: View) {
                if (v === codePanel) {
                    dl.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.END)
                    codePanel.setActive(false)
                }
            }
        })
        return dl
    }

    private fun styleRunChip() {
        runChip.setTextColor(if (onlyRunning) Color.WHITE else Theme.MUTED)
        runChip.background = if (onlyRunning) Md.gradient(this, Theme.ACCENT, Theme.ACCENT2, 14) else Md.round(Theme.SURFACE2, 14, this)
    }

    private fun openCode(tab: Int = -1) {
        codePanel.update(curId, thread, live?.steps?.values ?: emptyList())
        if (tab >= 0) codePanel.showTab(tab)
        drawer.openDrawer(Gravity.END)
    }

    private fun loadUsage() {
        pool.execute {
            val u = api.usage()
            ui.post {
                if (u == null || u.remaining < 0) return@post
                val reset = if (u.resetAt > 0) " · сброс " + java.text.SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(java.util.Date(u.resetAt)) else ""
                usageView.text = "Осталось ${u.remaining}% · ${u.member.ifEmpty { "free" }}$reset"
                usageView.setTextColor(if (u.remaining <= 10) Theme.AMBER else Theme.MUTED)
                usageView.visibility = View.VISIBLE
            }
        }
    }

    private fun restartUi(openSettings: Boolean) {
        val it = Intent(intent)
        if (curId != null) it.putExtra("task_id", curId).putExtra("task_title", curTitle)
        if (openSettings) it.putExtra("open_settings", true)
        setIntent(it)
        recreate()
    }

    private fun openSettings() {
        val dlg = Dialog(this, dlgTheme())
        dlg.window?.setBackgroundDrawable(ColorDrawable(Theme.BG))
        dlg.window?.statusBarColor = Theme.BG
        dlg.window?.navigationBarColor = Theme.BG
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Theme.light) dlg.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(12), 0) }
        bar.addView(icon(R.drawable.ic_back, "Назад") { dlg.dismiss() })
        bar.addView(TextView(this).apply { text = "Настройки"; setTextColor(Theme.TEXT); textSize = 18f; typeface = Typeface.DEFAULT_BOLD }, lp(0, -2, 1f).apply { leftMargin = dp(6) })
        root.addView(bar, lp(-1, dp(56)))
        root.addView(View(this).apply { setBackgroundColor(Theme.STROKE) }, lp(-1, 1))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(box) }, lp(-1, 0, 1f))

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Md.round(Theme.SURFACE, 18, this@MainActivity, Theme.STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Theme.MUTED); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), dp(18), 0, dp(8)) }
        fun editBg() = Md.round(Theme.SURFACE2, 12, this)
        fun edit(value: String, multi: Boolean) = EditText(this).apply {
            setText(value); setTextColor(Theme.TEXT); textSize = 14f
            background = editBg(); setPadding(dp(12), dp(10), dp(12), dp(10))
            if (multi) { minLines = 3; maxLines = 8; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            else { setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
        }

        box.addView(label("ОФОРМЛЕНИЕ"))
        val themes = listOf(
            Triple("dark", "Тёмная", Pair(Color.rgb(22, 24, 33), Color.rgb(139, 92, 246))),
            Triple("light", "Белая", Pair(Color.WHITE, Color.rgb(124, 77, 237))),
            Triple("teal", "Бирюзовая", Pair(Color.rgb(220, 242, 240), Color.rgb(13, 148, 136))))
        val tr = LinearLayout(this)
        for ((key, name, cols) in themes) {
            val on = Theme.mode == key
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = Md.rippleRect(Md.round(Theme.SURFACE, 16, this@MainActivity, if (on) Theme.ACCENT else Theme.STROKE))
                setPadding(dp(6), dp(12), dp(6), dp(10))
                setOnClickListener {
                    if (!on) { prefs.edit().putString("theme", key).apply(); dlg.dismiss(); restartUi(true) }
                }
            }
            t.addView(View(this).apply {
                background = Md.circle(cols.first).also { it.setStroke(dp(3), cols.second) }
            }, lp(dp(44), dp(44)))
            t.addView(TextView(this).apply { text = name; setTextColor(Theme.TEXT); textSize = 12.5f; setPadding(0, dp(8), 0, 0); typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT })
            tr.addView(t, lp(0, -2, 1f).apply { if (key != "dark") leftMargin = dp(8) })
        }
        box.addView(tr, lp(-1, -2))

        box.addView(label("РАЗМЕР ТЕКСТА"))
        val fc = card()
        val cur = prefs.getInt("fs", 100)
        val fl = TextView(this).apply { setTextColor(Theme.TEXT); textSize = 14f; text = "Масштаб: $cur%" }
        fc.addView(fl)
        val sb = android.widget.SeekBar(this).apply {
            max = 12
            progress = (cur - 70) / 5
            progressTintList = ColorStateList.valueOf(Theme.ACCENT)
            thumbTintList = ColorStateList.valueOf(Theme.ACCENT)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) { fl.text = "Масштаб: ${70 + p * 5}%" }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                    val v = 70 + (s?.progress ?: 6) * 5
                    if (v != cur) { prefs.edit().putInt("fs", v).apply(); dlg.dismiss(); restartUi(true) }
                }
            })
        }
        fc.addView(sb, lp(-1, -2).apply { topMargin = dp(6) })
        val ends = LinearLayout(this)
        ends.addView(TextView(this).apply { text = "70%"; setTextColor(Theme.MUTED); textSize = 11f }, lp(0, -2, 1f))
        ends.addView(TextView(this).apply { text = "130%"; setTextColor(Theme.MUTED); textSize = 11f })
        fc.addView(ends, lp(-1, -2))
        box.addView(fc, lp(-1, -2))

        box.addView(label("QWEN CODER"))
        val qc = card()
        val status = TextView(this).apply { text = "Загрузка настроек…"; setTextColor(Theme.MUTED); textSize = 13f }
        qc.addView(status)
        box.addView(qc, lp(-1, -2))
        dlg.setContentView(root)
        dlg.show()

        pool.execute {
            try {
                val st = api.getSettings()
                ui.post {
                    val cs = st.optJSONObject("code_settings") ?: JSONObject()
                    qc.removeAllViews()
                    fun cap(t: String) = TextView(this).apply { text = t; setTextColor(Theme.MUTED); textSize = 12f; setPadding(dp(2), dp(10), 0, dp(4)) }
                    qc.addView(cap("Свой промпт для кода").apply { setPadding(dp(2), 0, 0, dp(4)) })
                    val prompt = edit(cs.optString("custom_prompt"), true)
                    qc.addView(prompt, lp(-1, -2))
                    qc.addView(cap("Формат имени ветки"))
                    val branch = edit(cs.optString("branch_format"), false)
                    qc.addView(branch, lp(-1, -2))
                    qc.addView(cap("Вид diff"))
                    var diff = cs.optString("diff_display", "unified").ifEmpty { "unified" }
                    val dr = LinearLayout(this)
                    val dv = ArrayList<Pair<String, TextView>>()
                    fun paint() {
                        for ((k, v) in dv) {
                            val on = k == diff
                            v.setTextColor(if (on) Color.WHITE else Theme.TEXT)
                            v.background = if (on) Md.gradient(this, Theme.ACCENT, Theme.ACCENT2, 12) else editBg()
                        }
                    }
                    for ((k, n) in listOf("unified" to "Единый", "split" to "Раздельный")) {
                        val v = TextView(this).apply { text = n; textSize = 13.5f; gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener { diff = k; paint() } }
                        dv.add(k to v)
                        dr.addView(v, lp(0, -2, 1f).apply { if (k == "split") leftMargin = dp(8) })
                    }
                    paint()
                    qc.addView(dr, lp(-1, -2))
                    qc.addView(gradientButton("Сохранить") {
                        cs.put("custom_prompt", prompt.text.toString())
                        cs.put("branch_format", branch.text.toString())
                        cs.put("diff_display", diff)
                        st.put("code_settings", cs)
                        pool.execute {
                            try {
                                api.updateSettings(st)
                                ui.post { toast("Настройки сохранены") }
                            } catch (e: Exception) {
                                ui.post { handleError(e) }
                            }
                        }
                    }, lp(-1, -2).apply { topMargin = dp(16) })
                }
            } catch (e: Exception) {
                ui.post { status.text = "Не удалось загрузить настройки Qwen Coder: ${e.message ?: ""}" }
            }
        }
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getStringExtra("task_id") ?: return
        i.removeExtra("task_id")
        if (!api.loggedIn) return
        openTask(id, i.getStringExtra("task_title").orEmpty(), i.getStringExtra("task_repo").orEmpty(), i.getStringExtra("task_branch").orEmpty())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun buildEmpty(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(40), dp(22), dp(16))
        }
        box.addView(Md.logoView(this, 68), lp(dp(68), dp(68)))
        emptyTitle = TextView(this).apply {
            setTextColor(Theme.TEXT)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }
        emptySub = TextView(this).apply {
            setTextColor(Theme.MUTED)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        box.addView(emptyTitle, lp(-1, -2))
        box.addView(emptySub, lp(-1, -2))
        chipsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val prompts = listOf(
            "Проанализируй репозиторий и расскажи об архитектуре",
            "Найди баги и потенциальные проблемы в коде",
            "Напиши тесты для основных модулей",
            "Предложи улучшения и сделай рефакторинг"
        )
        for (p in prompts) {
            chipsBox.addView(TextView(this).apply {
                text = p
                setTextColor(Theme.TEXT)
                textSize = 14f
                background = Md.round(Theme.SURFACE, 14, this@MainActivity, Theme.STROKE)
                setPadding(dp(16), dp(13), dp(16), dp(13))
                setOnClickListener {
                    input.setText(p)
                    input.setSelection(p.length)
                    input.requestFocus()
                }
            }, lp(-1, -2).apply { topMargin = dp(8) })
        }
        box.addView(chipsBox, lp(-1, -2).apply { topMargin = dp(26) })
        return box
    }

    private fun updateFab() {
        val child = scroll.getChildAt(0) ?: return
        val dist = child.height - (scroll.height + scroll.scrollY)
        fab.visibility = if (dist > dp(260)) View.VISIBLE else View.GONE
    }

    // ---------- screens / session ----------

    private fun showLogin() {
        loginView.visibility = View.VISIBLE
        drawer.visibility = View.GONE
    }

    private fun updateAccount() {
        val n = api.userName.ifEmpty { api.userEmail }.ifEmpty { "Аккаунт Qwen" }
        nameView.text = n
        emailView.text = api.userEmail
        avatarView.text = n.firstOrNull()?.uppercase() ?: "Q"
    }

    private fun showMain() {
        loginView.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        updateAccount()
        updateHeader()
        render(emptyList())
        loadTasks()
        loadUsage()
        loadEnvs(false)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2002)
        pool.execute {
            try {
                api.me()
                ui.post { updateAccount() }
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
        selEnv = null
        selBranch = ""
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

    private fun doLogin(raw: String) {
        val (a, r) = TokenParser.extract(raw)
        if (a == null && r == null) {
            loginStatus.text = "В тексте не найден токен (строка, начинающаяся с eyJ…)"
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

    // ---------- repositories ----------

    private fun defaultBranch(e: Env): String =
        e.branches.firstOrNull { it == "main" } ?: e.branches.firstOrNull { it == "master" } ?: e.branches.firstOrNull().orEmpty()

    private fun loadEnvs(force: Boolean, done: (() -> Unit)? = null) {
        if (!force && envs != null) {
            restoreSelection()
            done?.invoke()
            return
        }
        pool.execute {
            try {
                val e = api.environments()
                ui.post {
                    envs = e
                    restoreSelection()
                    done?.invoke()
                }
            } catch (ex: Exception) {
                ui.post { handleError(ex) }
            }
        }
    }

    private fun restoreSelection() {
        val list = envs ?: return
        val keep = selEnv?.id ?: prefs.getString("last_env_id", null)
        val e = list.firstOrNull { it.id == keep }
        if (e != null) {
            selEnv = e
            val b = if (selBranch.isNotEmpty() && selEnv?.id == e.id && e.branches.contains(selBranch)) selBranch
            else prefs.getString("branch_" + e.id, null)?.takeIf { e.branches.contains(it) } ?: defaultBranch(e)
            selBranch = b
        } else {
            selEnv = null
            selBranch = ""
        }
        updateChips()
    }

    private fun updateChips() {
        val id = curId
        repoChip.text = if (id != null) {
            curRepo.ifEmpty { "Без репозитория" }
        } else {
            val e = selEnv
            if (e == null) "Выбрать репозиторий" else (e.repo.ifEmpty { e.name }) + if (selBranch.isNotEmpty()) " · $selBranch" else ""
        }
        repoChip.alpha = if (id != null) 0.7f else 1f
        modelChip.text = modelTitle(selModel)
        thinkChip.text = if (thinking) "Думать: вкл" else "Думать: выкл"
        thinkChip.setTextColor(if (thinking) Theme.ACCENT else Theme.TEXT)
    }

    private fun modelTitle(id: String): String = models.firstOrNull { it.id == id }?.title ?: id

    private fun isoAgo(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            val d = f.parse(iso) ?: return ""
            ago(d.time / 1000)
        } catch (_: Exception) {
            ""
        }
    }

    private fun openRepoPicker() {
        if (curId != null) {
            toast("Репозиторий открытой задачи изменить нельзя. Создайте новую задачу (+)")
            return
        }
        val dlg = Dialog(this, dlgTheme())
        dlg.window?.setBackgroundDrawable(ColorDrawable(Theme.BG))
        dlg.window?.statusBarColor = Theme.BG
        dlg.window?.navigationBarColor = Theme.BG
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
        }
        val backBtn = icon(R.drawable.ic_close, "Закрыть") { dlg.dismiss() }
        val topTitle = TextView(this).apply {
            setTextColor(Theme.TEXT)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, 0)
        }
        top.addView(backBtn)
        top.addView(topTitle, lp(0, -2, 1f))
        root.addView(top, lp(-1, dp(56)))
        root.addView(View(this).apply { setBackgroundColor(Theme.STROKE) }, lp(-1, 1))
        val search = EditText(this).apply {
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 15f
            setSingleLine()
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(Theme.MUTED)
            background = Md.round(Theme.SURFACE, 14, this@MainActivity, Theme.STROKE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        root.addView(search, lp(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(12); bottomMargin = dp(4)
        })
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        root.addView(ScrollView(this).apply { addView(listBox, FrameLayout.LayoutParams(-1, -2)) }, lp(-1, 0, 1f))

        var stage = 0
        var pickedEnv: Env? = null
        var ghState: Boolean? = null
        var filter = ""

        fun row(title: String, sub: String, selected: Boolean, iconRes: Int, onClick: () -> Unit): View {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Md.rippleRect(if (selected) Md.round(Theme.SURFACE2, 14, this@MainActivity, Theme.ACCENT) else Md.round(Theme.SURFACE, 14, this@MainActivity, Theme.STROKE))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { onClick() }
            }
            r.addView(ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(if (selected) Theme.ACCENT else Theme.MUTED)
            }, lp(dp(22), dp(22)))
            val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tb.addView(TextView(this).apply {
                text = title
                setTextColor(Theme.TEXT)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (sub.isNotEmpty()) tb.addView(TextView(this).apply {
                text = sub
                setTextColor(Theme.MUTED)
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            r.addView(tb, lp(0, -2, 1f).apply { leftMargin = dp(12) })
            if (selected) r.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(Theme.ACCENT)
            }, lp(dp(20), dp(20)))
            r.layoutParams = lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(8) }
            return r
        }

        lateinit var showRepos: () -> Unit
        lateinit var showBranches: () -> Unit

        showRepos = {
            stage = 0
            topTitle.text = "Репозиторий"
            backBtn.setImageResource(R.drawable.ic_close)
            search.hint = "Поиск репозитория"
            listBox.removeAllViews()
            val all = envs
            if (all == null) {
                listBox.addView(TextView(this).apply {
                    text = "Загрузка репозиториев…"
                    setTextColor(Theme.MUTED)
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                })
            } else {
                listBox.addView(row("Без репозитория", "Свободный чат без привязки к коду", selEnv == null, R.drawable.ic_sparkle) {
                    selEnv = null
                    selBranch = ""
                    prefs.edit().remove("last_env_id").apply()
                    updateChips()
                    dlg.dismiss()
                })
                val shownList = all.filter {
                    filter.isEmpty() || (it.repo + " " + it.name).lowercase().contains(filter.lowercase())
                }
                for (e in shownList) {
                    val parts = ArrayList<String>()
                    parts.add(if (e.isPrivate) "Приватный" else "Публичный")
                    if (e.branches.isNotEmpty()) parts.add(e.branches.size.toString() + " вет.")
                    val a = isoAgo(e.pushed)
                    if (a.isNotEmpty()) parts.add("обновлён $a назад")
                    listBox.addView(row(e.repo.ifEmpty { e.name }, parts.joinToString(" · "), selEnv?.id == e.id, R.drawable.ic_repo) {
                        pickedEnv = e
                        filter = ""
                        search.setText("")
                        showBranches()
                    })
                }
                if (shownList.isEmpty() && filter.isNotEmpty()) {
                    listBox.addView(TextView(this).apply {
                        text = "Ничего не найдено"
                        setTextColor(Theme.MUTED)
                        setPadding(dp(18), dp(14), dp(18), dp(6))
                    })
                }
                listBox.addView(sectionLabel("GitHub"))
                val gh = TextView(this).apply {
                    text = when (ghState) {
                        true -> "Аккаунт GitHub подключён к Qwen Coder"
                        false -> "GitHub не подключён к Qwen Coder. Подключите его на сайте"
                        null -> "Проверяю подключение GitHub…"
                    }
                    setTextColor(if (ghState == false) Theme.AMBER else Theme.MUTED)
                    textSize = 13f
                    setPadding(dp(18), 0, dp(18), dp(8))
                }
                listBox.addView(gh)
                listBox.addView(outlineButton("Добавить репозиторий на сайте Qwen") {
                    refreshEnvsOnResume = true
                    openUrl("https://coder.qwen.ai/")
                    dlg.dismiss()
                }, lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(4) })
                listBox.addView(outlineButton("Обновить список") {
                    envs = null
                    showRepos()
                    loadEnvs(true) { if (dlg.isShowing && stage == 0) showRepos() }
                }, lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(8) })
            }
        }

        showBranches = {
            val e = pickedEnv
            if (e != null) {
                stage = 1
                topTitle.text = e.repo.ifEmpty { e.name }
                backBtn.setImageResource(R.drawable.ic_back)
                search.hint = "Поиск ветки"
                listBox.removeAllViews()
                val bs = e.branches.sortedBy { if (it == "main" || it == "master") 0 else 1 }
                val curBranch = if (selEnv?.id == e.id) selBranch else prefs.getString("branch_" + e.id, "").orEmpty()
                val f = bs.filter { filter.isEmpty() || it.lowercase().contains(filter.lowercase()) }
                if (f.isEmpty()) {
                    listBox.addView(TextView(this).apply {
                        text = if (bs.isEmpty()) "Ветки не найдены. Нажмите «Обновить»" else "Ничего не найдено"
                        setTextColor(Theme.MUTED)
                        setPadding(dp(18), dp(18), dp(18), dp(8))
                    })
                }
                for (b in f) {
                    listBox.addView(row(b, if (b == "main" || b == "master") "Основная ветка" else "", b == curBranch, R.drawable.ic_repo) {
                        selEnv = e
                        selBranch = b
                        prefs.edit().putString("last_env_id", e.id).putString("branch_" + e.id, b).apply()
                        updateChips()
                        dlg.dismiss()
                    })
                }
                listBox.addView(outlineButton("Обновить ветки") {
                    pool.execute {
                        try {
                            val fresh = api.environment(e.id)
                            ui.post {
                                envs = envs?.map { if (it.id == fresh.id) fresh else it }
                                pickedEnv = fresh
                                if (dlg.isShowing && stage == 1) showBranches()
                            }
                        } catch (ex: Exception) {
                            ui.post { handleError(ex) }
                        }
                    }
                }, lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(12) })
            }
        }

        backBtn.setOnClickListener {
            if (stage == 1) {
                filter = ""
                search.setText("")
                showRepos()
            } else {
                dlg.dismiss()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString().orEmpty().trim()
                if (v != filter) {
                    filter = v
                    if (stage == 0) showRepos() else showBranches()
                }
            }
        })

        dlg.setContentView(root)
        showRepos()
        dlg.show()
        loadEnvs(envs == null) { if (dlg.isShowing && stage == 0) showRepos() }
        pool.execute {
            val g = api.githubConnected()
            ui.post {
                ghState = g
                if (dlg.isShowing && stage == 0) showRepos()
            }
        }
    }

    // ---------- models ----------

    private fun openModelPicker() {
        sheet("Модель") { box, dlg ->
            val custom = models.none { it.id == selModel }
            fun card(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = Md.rippleRect(Md.round(if (selected) Theme.SURFACE2 else Theme.SURFACE, 16, this@MainActivity, if (selected) Theme.ACCENT else Theme.STROKE))
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener { dlg.dismiss(); onClick() }
                }
                row.addView(Md.logoView(this, 34), lp(dp(34), dp(34)))
                val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                tb.addView(TextView(this).apply { text = title; setTextColor(Theme.TEXT); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD })
                tb.addView(TextView(this).apply { text = sub; setTextColor(Theme.MUTED); textSize = 12.5f })
                row.addView(tb, lp(0, -2, 1f).apply { leftMargin = dp(12) })
                if (selected) row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(Color.WHITE)
                    background = Md.circleGradient(Theme.ACCENT, Theme.ACCENT2)
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }, lp(dp(24), dp(24)))
                box.addView(row, lp(-1, -2).apply { bottomMargin = dp(8) })
            }
            for (m in models) card(m.title, m.hint, m.id == selModel) { setModel(m.id) }
            if (custom) card(selModel, "Свой ID модели", true) {}
            card("Свой ID…", "Указать идентификатор модели вручную", false) { askCustomModel() }
        }
    }

    /** Bottom sheet with rounded top, built in code. */
    private fun sheet(title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        box.addView(View(this).apply { background = Md.round(Theme.STROKE, 3, this@MainActivity) }, lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        if (title.isNotEmpty()) box.addView(TextView(this).apply {
            text = title; setTextColor(Theme.TEXT); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(12))
        })
        build(box, d)
        val r = dp(24).toFloat()
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Theme.BG)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        d.setContentView(ScrollView(this).apply { background = bg; addView(box) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f)
        }
        d.show()
        return d
    }

    private fun askCustomModel() {
        val et = EditText(this).apply {
            setSingleLine()
            hint = "например, qwen3.8-max"
            setText(selModel)
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("ID модели").setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) setModel(v)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun setModel(id: String) {
        selModel = id
        prefs.edit().putString("model", id).apply()
        updateChips()
    }

    // ---------- tasks ----------

    private fun statusLabel(s: String) = when (s) {
        "running" -> "выполняется"
        "finished" -> "готово"
        "" -> ""
        else -> s
    }

    private fun ago(sec: Long): String {
        if (sec <= 0) return ""
        val d = System.currentTimeMillis() / 1000 - sec
        return when {
            d < 60 -> "сейчас"
            d < 3600 -> "${d / 60} мин"
            d < 86400 -> "${d / 3600} ч"
            d < 86400 * 30 -> "${d / 86400} д"
            else -> "${d / (86400 * 30)} мес"
        }
    }

    private fun dayNumber(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L

    private fun groupOf(sec: Long): String {
        if (sec <= 0) return "Ранее"
        val diff = dayNumber(Calendar.getInstance().timeInMillis) - dayNumber(sec * 1000)
        return when {
            diff <= 0 -> "Сегодня"
            diff == 1L -> "Вчера"
            diff <= 7 -> "На этой неделе"
            else -> "Ранее"
        }
    }

    private fun loadTasks() {
        pool.execute {
            try {
                val t = api.tasks(1)
                val pn = try { api.pinned() } catch (_: Exception) { emptyList() }
                ui.post {
                    tasks = t
                    pinnedTasks = pn
                    renderTasks()
                }
            } catch (e: Exception) {
                ui.post { handleError(e) }
            }
        }
    }

    private fun renderTasks() {
        taskBox.removeAllViews()
        val pinIds = pinnedTasks.map { it.id }.toSet()
        fun ok(t: TaskItem) = (!onlyRunning || t.status == "running") && (query.isEmpty() || t.title.contains(query, true) || t.repo.contains(query, true))
        val pins = pinnedTasks.filter { ok(it) }
        val rest = tasks.filter { it.id !in pinIds && ok(it) }
        val all = pins + rest
        if (all.isEmpty()) {
            taskBox.addView(TextView(this).apply {
                text = if (tasks.isEmpty()) "Пока нет задач" else "Ничего не найдено"
                setTextColor(Theme.MUTED)
                setPadding(dp(18), dp(16), dp(18), dp(12))
            })
            return
        }
        var lastGroup = ""
        for (t in all) {
            val g = if (t.id in pinIds) "Закреплённые" else groupOf(t.updatedAt)
            if (g != lastGroup) {
                taskBox.addView(sectionLabel(g))
                lastGroup = g
            }
            val sel = t.id == curId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Md.rippleRect(if (sel) Md.round(Theme.SURFACE2, 12, this@MainActivity) else null)
                setPadding(dp(14), dp(10), dp(14), dp(10))
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
            row.addView(View(this).apply {
                background = Md.circle(if (t.status == "running") Theme.AMBER else if (t.status == "finished") Theme.GREEN else Theme.MUTED)
            }, lp(dp(8), dp(8)).apply { rightMargin = dp(12) })
            val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tb.addView(TextView(this).apply {
                text = t.title.ifEmpty { "Без названия" }
                setTextColor(Theme.TEXT)
                textSize = 14.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val sub = listOf(t.repo, t.branch).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) tb.addView(TextView(this).apply {
                text = sub
                setTextColor(Theme.MUTED)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(tb, lp(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = ago(t.updatedAt)
                setTextColor(Theme.MUTED)
                textSize = 11f
                setPadding(dp(8), 0, 0, 0)
            })
            row.layoutParams = lp(-1, -2).apply { leftMargin = dp(8); rightMargin = dp(8) }
            taskBox.addView(row)
        }
    }

    private fun deleteTask(id: String) {
        pool.execute {
            try {
                api.deleteTask(id)
                ui.post {
                    if (curId == id) {
                        resetChat(null, "", "", "")
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
        openSteps.clear()
        fetchWaiting = false
        previewWaiting = false
        sandboxFiles = if (id != null) Sandbox.loadCache(this, id) else null
        codePanel.setSandbox(sandboxFiles, "", false)
        updateSend()
        updateHeader()
        updateChips()
        shown = emptyList()
        list.removeAllViews()
        render(emptyList())
        renderTasks()
    }

    private fun startNewTask() {
        resetChat(null, "", "", "")
        drawer.closeDrawers()
        input.requestFocus()
        loadEnvs(false) {
            if (selEnv == null && !prefs.contains("last_env_id") && (envs?.isNotEmpty() == true)) openRepoPicker()
        }
    }

    private fun openTask(id: String, title: String, repo: String, branch: String) {
        resetChat(id, title, repo, branch)
        fetchDetail(id)
    }

    private fun setWorking(on: Boolean) {
        if (on) {
            statusDot.background = Md.circle(Theme.AMBER)
            if (statusAnim == null) {
                statusAnim = ObjectAnimator.ofFloat(statusDot, "alpha", 0.3f, 1f).apply {
                    duration = 700
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        } else {
            statusAnim?.cancel()
            statusAnim = null
            statusDot.alpha = 1f
            statusDot.background = Md.circle(Theme.GREEN)
        }
    }

    private fun updateHeader() {
        val working = streaming || running
        titleView.text = if (curId == null) "Новая задача" else curTitle.ifEmpty { "Qwen Coder" }
        if (curId == null) {
            statusDot.visibility = View.GONE
            setWorking(false)
            subtitleView.text = "Выберите репозиторий и модель"
            subtitleView.setTextColor(Theme.MUTED)
            emptyTitle.text = "Что будем делать?"
            emptySub.text = "Выберите репозиторий и модель внизу,\nзатем опишите задачу"
        } else {
            statusDot.visibility = View.VISIBLE
            setWorking(working)
            subtitleView.text = if (working) "Работает…" else curRepo.ifEmpty { "Готов" }
            subtitleView.setTextColor(if (working) Theme.AMBER else Theme.MUTED)
            emptyTitle.text = "Задача пуста"
            emptySub.text = "Напишите сообщение, чтобы продолжить"
        }
    }

    private fun updateSend() {
        sendBtn.setImageResource(if (streaming) R.drawable.ic_stop else R.drawable.ic_send)
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
        updateChips()
        rebuildDisplay()
        if (!running && !streaming) checkSandbox()
    }

    // ---------- sandbox ----------

    private fun agentBusy(): Boolean {
        if (curId == null) { toast("Сначала откройте задачу"); return true }
        if (streaming || running) { toast("Дождитесь, пока агент закончит текущую работу"); return true }
        return false
    }

    private fun fetchSandbox() {
        val id = curId
        if (agentBusy() || id == null) return
        fetchWaiting = true
        codePanel.setSandbox(sandboxFiles, "", true)
        startStream(id, Sandbox.fetchPrompt())
    }

    private fun startPreviewServer() {
        val id = curId
        if (agentBusy() || id == null) return
        toast("Агент запускает сервер превью…")
        pool.execute {
            val port = api.sandboxPort(id)
            ui.post {
                if (curId != id) return@post
                previewWaiting = true
                startStream(id, Sandbox.previewPrompt(port))
            }
        }
    }

    private fun checkSandbox() {
        val id = curId ?: return
        if (previewWaiting && thread.lastOrNull()?.role == "assistant") {
            previewWaiting = false
            codePanel.reloadPreview()
            toast("Сервер превью запущен — откройте «Превью»")
        }
        if (!fetchWaiting && sandboxFiles != null) return
        val r = Sandbox.extract(thread)
        if (r == null) {
            if (fetchWaiting) { fetchWaiting = false; codePanel.setSandbox(sandboxFiles, "Запрос не найден в истории. Попробуйте ещё раз.", false) }
            return
        }
        val files = r.files
        if (files != null) {
            val was = fetchWaiting
            fetchWaiting = false
            sandboxFiles = files
            codePanel.setSandbox(files, "", false)
            pool.execute { Sandbox.saveCache(this, id, files) }
            if (was) toast("Получено файлов: ${files.size}")
        } else if (fetchWaiting) {
            fetchWaiting = false
            val msg = if (r.total == 0) "Агент не вывел архив. Нажмите «Получить из песочницы» ещё раз."
            else "Получено частей ${r.got} из ${r.total}. Нажмите кнопку ещё раз, чтобы повторить."
            codePanel.setSandbox(sandboxFiles, msg, false)
        }
    }

    private fun saveDownload(name: String, mime: String, bytes: ByteArray): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        return true
    }

    private fun saveSandbox() {
        val files = sandboxFiles ?: return
        val id = curId ?: return
        pool.execute {
            try {
                val name = "qwen-${curTitle.ifEmpty { id.take(8) }.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").take(40)}.tar.gz"
                val ok = saveDownload(name, "application/gzip", Sandbox.packTgz(files))
                ui.post { toast(if (ok) "Сохранено в Загрузки: $name" else "Сохранение доступно на Android 10+") }
            } catch (e: Exception) {
                ui.post { toast("Не удалось сохранить: ${e.message}") }
            }
        }
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
        codePanel.update(curId, thread, if (streaming) live?.steps?.values ?: emptyList() else emptyList())
        val n = codePanel.changeCount
        codeBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
        codeBadge.text = if (n > 99) "99" else n.toString()
    }

    // ---------- rendering ----------

    private fun render(display: List<ChatMsg>) {
        emptyBox.visibility = if (display.isEmpty()) View.VISIBLE else View.GONE
        chipsBox.visibility = if (display.isEmpty() && curId == null) View.VISIBLE else View.GONE
        if (display == shown) return
        val child = scroll.getChildAt(0)
        val atBottom = child == null || child.height - (scroll.height + scroll.scrollY) < dp(180)
        for (i in display.indices) {
            if (i < shown.size && shown[i] == display[i]) continue
            val v = makeView(display[i])
            if (i < list.childCount) list.removeViewAt(i)
            list.addView(v, i)
        }
        while (list.childCount > display.size) list.removeViewAt(list.childCount - 1)
        shown = display
        if (atBottom) scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }
        scroll.post { updateFab() }
    }

    private fun makeView(m: ChatMsg): View = if (m.role == "user") userView(m) else assistantView(m)

    private fun userView(m: ChatMsg): View {
        if (Sandbox.isService(m.text)) {
            val w = LinearLayout(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = lp(-1, -2).apply { bottomMargin = dp(14) }
            }
            w.addView(TextView(this).apply {
                text = if (Sandbox.isFetch(m.text)) "⚙  Запрос файлов из песочницы" else "⚙  Запуск сервера превью"
                setTextColor(Theme.MUTED)
                textSize = 12.5f
                background = Md.round(Theme.SURFACE2, 14, this@MainActivity, Theme.STROKE)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            })
            return w
        }
        val wrap = LinearLayout(this).apply {
            gravity = Gravity.END
            layoutParams = lp(-1, -2).apply { bottomMargin = dp(16) }
        }
        wrap.addView(TextView(this).apply {
            text = m.text
            setTextColor(Color.WHITE)
            textSize = 15.5f
            setTextIsSelectable(true)
            background = Md.gradient(this@MainActivity, Theme.USER1, Theme.USER2, 20)
            setPadding(dp(15), dp(11), dp(15), dp(11))
            maxWidth = (resources.displayMetrics.widthPixels * 0.84).toInt()
        })
        return wrap
    }

    private fun assistantView(m: ChatMsg): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(-1, -2).apply { bottomMargin = dp(22) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(Md.logoView(this, 20), lp(dp(20), dp(20)))
        head.addView(TextView(this).apply {
            text = "Qwen Coder"
            setTextColor(Theme.MUTED)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        })
        col.addView(head)
        if (m.thinking.isNotBlank()) col.addView(thinkingView(m))
        if (m.steps.isNotEmpty()) col.addView(stepsView(m))
        if (m.text.isNotBlank()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            Md.render(this, box, m.text)
            col.addView(box)
        } else if (m.error == null) {
            if (m.done) {
                col.addView(TextView(this).apply {
                    text = "(пустой ответ)"
                    setTextColor(Theme.MUTED)
                    textSize = 14f
                })
            } else {
                col.addView(typingView())
            }
        }
        if (m.error != null) {
            col.addView(TextView(this).apply {
                text = "Ошибка: " + m.error
                setTextColor(Theme.RED)
                textSize = 14f
                background = Md.round(Color.argb(30, 248, 113, 113), 10, this@MainActivity)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })
        }
        if (m.done && m.text.isNotBlank() && m.id != "live" && m.id != "wait") {
            col.addView(TextView(this).apply {
                text = "Копировать ответ"
                setTextColor(Theme.MUTED)
                textSize = 12f
                setPadding(0, dp(6), dp(12), dp(6))
                setOnClickListener { Md.copy(this@MainActivity, m.text) }
            })
        }
        return col
    }

    private fun typingView(): View {
        val tv = TextView(this).apply {
            text = "●  ●  ●"
            setTextColor(Theme.ACCENT)
            textSize = 12f
            setPadding(dp(2), dp(4), 0, dp(4))
        }
        val anim = ObjectAnimator.ofFloat(tv, "alpha", 0.25f, 1f).apply {
            duration = 750
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                anim.cancel()
            }
        })
        return tv
    }

    private fun thinkingView(m: ChatMsg): View {
        val key = "think:" + m.id
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Md.round(Theme.SURFACE, 12, this@MainActivity, Theme.STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = lp(-1, -2).apply { bottomMargin = dp(10) }
        }
        val body = TextView(this).apply {
            text = m.thinking
            setTextColor(Theme.MUTED)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            setPadding(0, dp(6), 0, dp(2))
            setTextIsSelectable(true)
            visibility = if (openSteps.contains(key)) View.VISIBLE else View.GONE
        }
        card.addView(TextView(this).apply {
            text = "Размышления  ▾"
            setTextColor(Theme.MUTED)
            textSize = 12.5f
            setPadding(0, dp(2), 0, dp(2))
            setOnClickListener {
                if (body.visibility == View.VISIBLE) {
                    body.visibility = View.GONE
                    openSteps.remove(key)
                } else {
                    body.visibility = View.VISIBLE
                    openSteps.add(key)
                }
            }
        })
        card.addView(body)
        return card
    }

    private fun stepsView(m: ChatMsg): View {
        val key = "card:" + m.id
        val defaultCollapsed = m.steps.size > 5
        val collapsed = defaultCollapsed != cardToggled.contains(key)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Md.round(Theme.SURFACE, 14, this@MainActivity, Theme.STROKE)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = lp(-1, -2).apply { bottomMargin = dp(12) }
        }
        val rowsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        val working = m.steps.any { it.status == "running" } || !m.done
        val title = TextView(this).apply {
            text = "Действия агента · ${m.steps.size}" + if (collapsed) "   ▸" else "   ▾"
            setTextColor(if (working) Theme.AMBER else Theme.MUTED)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        }
        title.setOnClickListener {
            val nowCollapsed = rowsBox.visibility == View.VISIBLE
            rowsBox.visibility = if (nowCollapsed) View.GONE else View.VISIBLE
            title.text = "Действия агента · ${m.steps.size}" + if (nowCollapsed) "   ▸" else "   ▾"
            if (nowCollapsed == defaultCollapsed) cardToggled.remove(key) else cardToggled.add(key)
        }
        card.addView(title)
        for (s in m.steps) {
            val ok = s.status == "finished" || s.status == "done" || s.status == "success" || s.status == "completed"
            val bad = s.status == "failed" || s.status == "error"
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val logView = TextView(this).apply {
                text = Sandbox.prettyLog(s.log).let { if (it.length > 6000) it.take(6000) + "\n…" else it.ifEmpty { "(нет вывода)" } }
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(Theme.CODE_FG)
                setTextIsSelectable(true)
                background = Md.round(Theme.CODE_BG, 8, this@MainActivity)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                visibility = if (openSteps.contains(s.id)) View.VISIBLE else View.GONE
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    if (logView.visibility == View.VISIBLE) {
                        logView.visibility = View.GONE
                        openSteps.remove(s.id)
                    } else {
                        logView.visibility = View.VISIBLE
                        openSteps.add(s.id)
                    }
                }
            }
            line.addView(TextView(this).apply {
                text = if (ok) "✓" else if (bad) "✕" else "●"
                setTextColor(if (ok) Theme.GREEN else if (bad) Theme.RED else Theme.AMBER)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }, lp(dp(20), -2))
            line.addView(TextView(this).apply {
                text = s.type.ifEmpty { "Шаг" }
                setTextColor(Theme.MUTED)
                textSize = 11f
                background = Md.round(Theme.SURFACE2, 6, this@MainActivity)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, lp(-2, -2).apply { leftMargin = dp(4); rightMargin = dp(8) })
            line.addView(TextView(this).apply {
                text = s.action.replace("\n", " ")
                setTextColor(Theme.TEXT)
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(0, -2, 1f))
            row.addView(line)
            row.addView(logView, lp(-1, -2).apply { bottomMargin = dp(6) })
            rowsBox.addView(row)
        }
        card.addView(rowsBox)
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
        if (id != null) {
            input.setText("")
            startStream(id, t)
            return
        }
        val env = selEnv
        val branch = selBranch
        val mdl = selModel
        input.setText("")
        pending = t
        pendingBase = 0
        streaming = true
        updateSend()
        rebuildDisplay()
        pool.execute {
            try {
                val newId = api.newTask(env, branch, mdl)
                ui.post {
                    streaming = false
                    resetChat(newId, "Новый чат", env?.repo ?: "", branch)
                    startStream(newId, t)
                    loadTasks()
                }
            } catch (e: Exception) {
                ui.post {
                    streaming = false
                    pending = null
                    updateSend()
                    input.setText(t)
                    rebuildDisplay()
                    handleError(e)
                }
            }
        }
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
        val mdl = selModel
        val think = thinking
        pool.execute {
            var err: String? = null
            try {
                api.stream(id, text, parent, mdl, think) { data -> ui.post { applyLive(id, data) } }
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

    private fun exportTask(id: String) {
        pool.execute {
            try {
                val txt = api.taskDetail(id).toString(1)
                val name = "qwen-task-${id.take(8)}.json"
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val cv = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { it.write(txt.toByteArray()) }
                    ui.post { toast("Сохранено в Загрузки: $name") }
                } else {
                    ui.post { Md.copy(this, txt.take(300_000)) }
                }
            } catch (e: Exception) {
                ui.post { toast("Не удалось сохранить: ${e.message}") }
            }
        }
    }

    private fun showMenu() {
        val id = curId
        sheet("Qwen Coder") { box, dlg ->
            fun item(t: String, sub: String = "", color: Int = Theme.TEXT, a: () -> Unit) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Md.rippleRect(Md.round(Theme.SURFACE, 14, this@MainActivity, Theme.STROKE))
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setOnClickListener { dlg.dismiss(); a() }
                }
                row.addView(TextView(this).apply { text = t; setTextColor(color); textSize = 15.5f })
                if (sub.isNotEmpty()) row.addView(TextView(this).apply { text = sub; setTextColor(Theme.MUTED); textSize = 12.5f })
                box.addView(row, lp(-1, -2).apply { bottomMargin = dp(8) })
            }
            item("Настройки", "Тема, размер текста, настройки Qwen Coder") { openSettings() }
            if (id != null) {
                item("Превью проекта", "Открыть в окне кода") { openCode(3) }
                item("Файлы из песочницы", "Выгрузить проект через агента") { openCode(1) }
                item("Открыть задачу на сайте") { openUrl("https://coder.qwen.ai/c/$id") }
                item("Сохранить данные задачи", "Файл JSON в «Загрузки» для отладки") { exportTask(id) }
            }
            item("Репозитории и GitHub", "Подключить на сайте, список обновится сам") {
                refreshEnvsOnResume = true
                openUrl("https://coder.qwen.ai/")
            }
            item("Обновить всё") {
                envs = null
                loadEnvs(true)
                loadTasks()
                loadUsage()
                if (id != null) fetchDetail(id)
            }
            item("Выйти", "", Theme.RED) { logout() }
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
            toast("Служба распознавания речи недоступна")
            return
        }
        recognizer?.destroy()
        codePanel.destroy()
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
        micBtn.setColorFilter(Theme.RED)
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }

    private fun finishDictation() {
        listening = false
        micBtn.setColorFilter(Theme.MUTED)
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

    override fun onPause() {
        WatchService.visible = false
        if (api.loggedIn && (streaming || running)) WatchService.start(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        WatchService.visible = true
        WatchService.stop(this)
        if (api.loggedIn) loadTasks()
        if ((streaming || running) && curId != null) startPolling()
        if (refreshEnvsOnResume && api.loggedIn) {
            refreshEnvsOnResume = false
            envs = null
            loadEnvs(true) { toast("Список репозиториев обновлён") }
        }
    }

    @Deprecated("Legacy back handling")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.END) || drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawers() else super.onBackPressed()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        statusAnim?.cancel()
        api.cancelStream()
        recognizer?.destroy()
        pool.shutdownNow()
        super.onDestroy()
    }
}
