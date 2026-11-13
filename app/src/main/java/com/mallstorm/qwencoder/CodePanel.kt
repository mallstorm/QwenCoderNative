package com.mallstorm.qwencoder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Right-side "code window": changes (diff), terminal, live preview. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CodePanel(
    ctx: Context,
    private val changes: (Collection<Step>) -> List<FileChange>,
    private val onClose: () -> Unit,
    private val onOpenBrowser: (String) -> Unit,
    private val onFetch: () -> Unit,
    private val onStartPreview: () -> Unit,
    private val onSave: () -> Unit,
    private val onFile: (String, ByteArray, Boolean) -> Unit = { _, _, _ -> }
) : LinearLayout(ctx) {

    private var tab = 0
    private var files: List<FileChange> = emptyList()
    private var shells: List<Step> = emptyList()
    private var edits: List<Step> = emptyList()
    @Volatile private var virtual: Map<String, ByteArray> = emptyMap()
    private var fromChanges: Map<String, ByteArray> = emptyMap()
    private var sandbox: Map<String, ByteArray>? = null
    private var sandboxStatus = ""
    private var sandboxBusy = false
    private var localMode = false
    private var modeView: TextView? = null
    private var taskId: String? = null
    private var commit = ""
    private var sig = ""
    private var active = false
    private val expanded = HashSet<String>()
    private var fileQuery = ""
    private val tabViews = ArrayList<TextView>()
    private val body = FrameLayout(ctx)
    private val titleView: TextView
    private var web: WebView? = null
    private var webFor: String? = null

    val changeCount: Int get() = files.size

    private fun dp(v: Int) = Md.dp(context, v)

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.BG)
        isClickable = true
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(6), dp(6))
        }
        titleView = TextView(ctx).apply {
            text = "Код"
            setTextColor(Theme.TEXT)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        head.addView(titleView, LayoutParams(0, -2, 1f))
        head.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Theme.TEXT)
            background = Md.rippleCircle()
            setOnClickListener { onClose() }
        }, LayoutParams(dp(42), dp(42)))
        addView(head, LayoutParams(-1, -2))

        val tabs = LinearLayout(ctx).apply { setPadding(dp(12), 0, dp(12), dp(8)) }
        listOf("Изменения", "Файлы", "Терминал", "Превью").forEachIndexed { i, n ->
            val t = TextView(ctx).apply {
                text = n
                textSize = 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { tab = i; styleTabs(); render() }
            }
            tabViews.add(t)
            tabs.addView(t, LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(5) })
        }
        addView(tabs, LayoutParams(-1, -2))
        addView(View(ctx).apply { setBackgroundColor(Theme.STROKE) }, LayoutParams(-1, 1))
        addView(body, LayoutParams(-1, 0, 1f))
        styleTabs()
    }

    private fun styleTabs() {
        tabViews.forEachIndexed { i, t ->
            val on = i == tab
            t.setTextColor(if (on) Color.WHITE else Theme.MUTED)
            t.background = if (on) Md.gradient(context, Theme.ACCENT, Theme.ACCENT2, 12) else Md.round(Theme.SURFACE, 12, context, Theme.STROKE)
        }
    }

    /** Feed the latest thread; cheap when nothing changed. */
    fun update(id: String?, msgs: List<ChatMsg>, live: Collection<Step>) {
        val f = LinkedHashMap<String, FileChange>()
        val sh = LinkedHashMap<String, Step>()
        val all = ArrayList<Step>()
        var cm = ""
        for (m in msgs) {
            if (m.role != "assistant") continue
            for (s in m.steps) {
                if (s.type.equals("Shell", true)) sh[m.id + s.id] = s
                all.add(s)
            }
            for (c in m.files) f[norm(c.path)] = c
            if (m.commit.isNotEmpty()) cm = m.commit
        }
        all.addAll(live)
        // server sent no file_list for these: rebuild from tool-call arguments
        for (c in changes(all)) if (!f.containsKey(norm(c.path))) f[norm(c.path)] = c
        files = f.values.toList()
        shells = sh.values.toList()
        edits = all.filter { it.type.equals("Edit", true) && (it.action.startsWith("edit") || it.action.startsWith("create") || it.action.startsWith("write")) }
        fromChanges = f.filter { it.value.full }.mapValues { it.value.newText.toByteArray() }
        mergeVirtual()
        commit = cm
        if (taskId != id) {
            localMode = false
            expanded.clear()
        }
        taskId = id
        val s = "$id|${files.size}|${files.sumOf { it.newText.length + it.oldText.length }}|${shells.size}|${shells.lastOrNull()?.log?.length}|${shells.lastOrNull()?.status}|${edits.size}|${sandbox?.size}|$sandboxStatus|$sandboxBusy"
        if (s == sig) return
        sig = s
        titleView.text = if (files.isEmpty()) "Код" else "Код · ${files.size} ${if (files.size == 1) "файл" else "файлов"}"
        if (active && tab != 3) render()
    }

    private fun mergeVirtual() {
        val m = LinkedHashMap<String, ByteArray>(fromChanges)
        sandbox?.let { m.putAll(it) }
        virtual = m
    }

    /** Files unpacked from the sandbox (null = not fetched yet). */
    fun setSandbox(files: Map<String, ByteArray>?, status: String, busy: Boolean) {
        sandbox = files
        sandboxStatus = status
        sandboxBusy = busy
        mergeVirtual()
        sig = ""
        if (active && tab == 1) render()
    }

    fun reloadPreview() {
        localMode = false
        modeView?.text = ""
        val id = taskId ?: return
        web?.loadUrl("https://$id.preview.qwenlm.io/")
    }

    private fun norm(p: String) = p.removePrefix("/workspace/").removePrefix("./")

    fun setActive(on: Boolean) {
        active = on
        if (on) render()
    }

    fun showTab(i: Int) {
        tab = i
        styleTabs()
        if (active) render()
    }

    private fun render() {
        when (tab) {
            0 -> renderChanges()
            1 -> renderFiles()
            2 -> renderTerminal()
            else -> renderPreview()
        }
    }

    private fun scrollOf(content: LinearLayout): View {
        body.removeAllViews()
        val sv = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        body.addView(sv, FrameLayout.LayoutParams(-1, -1))
        return sv
    }

    private fun empty(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Theme.MUTED)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(30), dp(60), dp(30), dp(30))
    }

    private fun renderChanges() {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        if (files.isEmpty()) {
            if (edits.isEmpty()) {
                box.addView(empty("Пока нет изменений.\nКогда агент поменяет файлы, их diff появится здесь."))
            } else {
                box.addView(TextView(context).apply {
                    text = "Агент менял файлы, но сервер не прислал их содержимое:"
                    setTextColor(Theme.MUTED)
                    textSize = 13f
                    setPadding(dp(4), 0, 0, dp(10))
                })
                for (e in edits) box.addView(TextView(context).apply {
                    text = e.action
                    setTextColor(Theme.TEXT)
                    typeface = Typeface.MONOSPACE
                    textSize = 12.5f
                    background = Md.round(Theme.SURFACE, 12, context, Theme.STROKE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        } else {
            var add = 0
            var del = 0
            val diffs = files.map { c ->
                val d = DiffUtil.diff(c.oldText, c.newText)
                add += d.added
                del += d.removed
                d
            }
            box.addView(TextView(context).apply {
                val sb = SpannableStringBuilder("${files.size} файл. · ")
                val a = sb.length
                sb.append("+$add")
                sb.setSpan(ForegroundColorSpan(Theme.GREEN), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" ")
                val b = sb.length
                sb.append("−$del")
                sb.setSpan(ForegroundColorSpan(Theme.RED), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (commit.isNotEmpty()) sb.append("  · коммит ${commit.take(7)}")
                text = sb
                setTextColor(Theme.MUTED)
                textSize = 13f
                setPadding(dp(4), 0, 0, dp(10))
            })
            files.forEachIndexed { i, c -> box.addView(fileCard(c, diffs[i])) }
        }
        scrollOf(box)
    }

    private fun fileCard(c: FileChange, d: DiffResult): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Md.round(Theme.SURFACE, 14, context, Theme.STROKE)
        }
        val open = expanded.contains(c.path)
        val head = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = Md.rippleRect(null)
        }
        val name = c.path.substringAfterLast('/')
        val dir = c.path.substringBeforeLast('/', "")
        val tb = LinearLayout(context).apply { orientation = VERTICAL }
        tb.addView(TextView(context).apply {
            text = name
            setTextColor(Theme.TEXT)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (dir.isNotEmpty()) tb.addView(TextView(context).apply {
            text = dir
            setTextColor(Theme.MUTED)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        })
        head.addView(tb, LayoutParams(0, -2, 1f))
        head.addView(TextView(context).apply {
            val sb = SpannableStringBuilder()
            if (c.pending) sb.append("… ")
            val a = sb.length
            sb.append("+${d.added}")
            sb.setSpan(ForegroundColorSpan(Theme.GREEN), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
            val b = sb.length
            sb.append("−${d.removed}")
            sb.setSpan(ForegroundColorSpan(Theme.RED), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 12.5f
            setTextColor(Theme.MUTED)
            setPadding(dp(8), 0, 0, 0)
        })
        card.addView(head, LayoutParams(-1, -2))
        if (open) {
            card.addView(View(context).apply { setBackgroundColor(Theme.STROKE) }, LayoutParams(-1, 1))
            card.addView(diffView(d, c))
            card.addView(TextView(context).apply {
                text = "Копировать новый файл"
                setTextColor(Theme.ACCENT)
                textSize = 12.5f
                setPadding(dp(14), dp(10), dp(14), dp(12))
                setOnClickListener { Md.copy(context, c.newText) }
            })
        }
        head.setOnClickListener {
            if (!expanded.add(c.path)) expanded.remove(c.path)
            render()
        }
        card.layoutParams = LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        return card
    }

    private fun diffView(d: DiffResult, c: FileChange): View {
        val sb = SpannableStringBuilder()
        val ls = d.lines
        // collapse long unchanged stretches
        val keep = BooleanArray(ls.size)
        for (i in ls.indices) if (ls[i].kind != ' ') for (k in maxOf(0, i - 3)..minOf(ls.size - 1, i + 3)) keep[k] = true
        val allCtx = ls.none { it.kind != ' ' }
        var shownLines = 0
        var skipped = false
        for (i in ls.indices) {
            if (!allCtx && !keep[i]) {
                if (!skipped) {
                    sb.append("  ⋯\n")
                    sb.setSpan(ForegroundColorSpan(Theme.MUTED), sb.length - 4, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    skipped = true
                }
                continue
            }
            skipped = false
            if (shownLines++ > 600) {
                sb.append("… ещё ${ls.size - i} строк\n")
                break
            }
            val l = ls[i]
            val start = sb.length
            val no = if (l.kind == '-') l.oldNo else l.newNo
            sb.append(String.format("%4d %c %s\n", no, l.kind, l.text))
            when (l.kind) {
                '+' -> {
                    sb.setSpan(BackgroundColorSpan(Color.argb(60, 74, 222, 128)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Theme.ADD_FG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                '-' -> {
                    sb.setSpan(BackgroundColorSpan(Color.argb(60, 248, 113, 113)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Theme.DEL_FG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> sb.setSpan(ForegroundColorSpan(Theme.MUTED), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val tv = TextView(context).apply {
            text = sb
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setHorizontallyScrolling(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Theme.CODE_BG)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tv, FrameLayout.LayoutParams(-2, -2))
        }
    }

    private fun renderFiles() {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        val sb = sandbox
        val head = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Md.round(Theme.SURFACE, 14, context, Theme.STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        head.addView(TextView(context).apply {
            text = when {
                taskId == null -> "Файлы доступны внутри задачи."
                sandboxBusy -> "Агент выгружает файлы из песочницы…"
                sandboxStatus.isNotEmpty() -> sandboxStatus
                sb == null -> "Файлы проекта лежат в песочнице Qwen. Приложение попросит агента упаковать их и вывести в терминал, затем соберёт архив у себя. Это обычный запрос к агенту: он тратит кредиты и занимает пару минут."
                else -> "Из песочницы: ${sb.size} файл., ${sb.values.sumOf { it.size } / 1024} КБ"
            }
            setTextColor(Theme.MUTED)
            textSize = 13f
        })
        if (taskId != null && !sandboxBusy) {
            val row = LinearLayout(context).apply { setPadding(0, dp(10), 0, 0) }
            row.addView(TextView(context).apply {
                text = if (sb == null) "Получить из песочницы" else "Обновить"
                setTextColor(Color.WHITE)
                textSize = 13.5f
                gravity = Gravity.CENTER
                background = Md.gradient(context, Theme.ACCENT, Theme.ACCENT2, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { onFetch() }
            }, LayoutParams(0, -2, 1f))
            if (sb != null) row.addView(TextView(context).apply {
                text = "Сохранить архив"
                setTextColor(Theme.ACCENT)
                textSize = 13.5f
                gravity = Gravity.CENTER
                background = Md.round(Theme.SURFACE2, 12, context)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { onSave() }
            }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            head.addView(row, LayoutParams(-1, -2))
        }
        box.addView(head, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (sb != null) {
            val search = android.widget.EditText(context).apply {
                hint = "Поиск по файлам и их содержимому"
                setHintTextColor(Theme.MUTED)
                setTextColor(Theme.TEXT)
                textSize = 13.5f
                setSingleLine()
                background = Md.round(Theme.SURFACE2, 12, context)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setText(fileQuery)
                setSelection(text.length)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { v, _, _ ->
                    fileQuery = v.text.toString().trim()
                    render()
                    true
                }
            }
            box.addView(search, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val q = fileQuery.lowercase()
            var shownCount = 0
            for ((path, bytes) in sb) {
                var hits = 0
                if (q.isNotEmpty()) {
                    val inName = path.lowercase().contains(q)
                    if (Sandbox.isText(bytes)) {
                        var from = 0
                        val txt = String(bytes, Charsets.UTF_8).lowercase()
                        while (hits < 99) {
                            val i = txt.indexOf(q, from)
                            if (i < 0) break
                            hits++
                            from = i + q.length
                        }
                    }
                    if (!inName && hits == 0) continue
                }
                shownCount++
                val key = "f:$path"
                val card = LinearLayout(context).apply {
                    orientation = VERTICAL
                    background = Md.round(Theme.SURFACE, 12, context, Theme.STROKE)
                }
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = Md.rippleRect(null)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener {
                        if (!expanded.add(key)) expanded.remove(key)
                        render()
                    }
                }
                row.addView(TextView(context).apply {
                    text = path
                    setTextColor(Theme.TEXT)
                    typeface = Typeface.MONOSPACE
                    textSize = 12.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.START
                }, LayoutParams(0, -2, 1f))
                row.addView(TextView(context).apply {
                    text = (if (hits > 0) "совп. $hits · " else "") + if (bytes.size < 1024) "${bytes.size} Б" else "${bytes.size / 1024} КБ"
                    setTextColor(Theme.MUTED)
                    textSize = 11.5f
                    setPadding(dp(8), 0, 0, 0)
                })
                card.addView(row, LayoutParams(-1, -2))
                if (expanded.contains(key)) {
                    val acts = LinearLayout(context).apply { setPadding(dp(12), 0, dp(12), dp(6)) }
                    for ((label, share) in listOf("Отправить" to true, "Сохранить" to false)) {
                        acts.addView(TextView(context).apply {
                            text = label
                            setTextColor(Theme.ACCENT)
                            textSize = 12.5f
                            background = Md.round(Theme.SURFACE2, 10, context)
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                            setOnClickListener { onFile(path, bytes, share) }
                        }, LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                    }
                    card.addView(acts, LayoutParams(-1, -2))
                    if (Sandbox.isText(bytes)) {
                        val t = String(bytes, Charsets.UTF_8)
                        card.addView(Md.codeBlock(context, if (t.length > 60000) t.take(60000) + "\n…" else t, path.substringAfterLast('.', "")))
                    } else {
                        card.addView(TextView(context).apply {
                            text = "Бинарный файл"
                            setTextColor(Theme.MUTED)
                            textSize = 12.5f
                            setPadding(dp(12), 0, dp(12), dp(10))
                        })
                    }
                }
                box.addView(card, LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
            if (q.isNotEmpty() && shownCount == 0) box.addView(empty("Ничего не найдено."))
        }
        scrollOf(box)
    }

    private fun renderTerminal() {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        if (shells.isEmpty()) {
            box.addView(empty("Команды агента появятся здесь."))
        } else {
            for (s in shells) {
                val sb = SpannableStringBuilder()
                sb.append("$ ")
                sb.append(s.action.trim())
                sb.setSpan(ForegroundColorSpan(Theme.GREEN), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val out = Sandbox.prettyLog(s.log).trim()
                if (out.isNotEmpty()) {
                    sb.append("\n")
                    val a = sb.length
                    sb.append(if (out.length > 6000) out.take(6000) + "\n…" else out)
                    sb.setSpan(ForegroundColorSpan(Theme.CODE_FG), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (s.status != "finished") {
                    sb.append("\n…")
                }
                val tv = TextView(context).apply {
                    text = sb
                    typeface = Typeface.MONOSPACE
                    textSize = 11.5f
                    setTextIsSelectable(true)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = Md.round(Theme.CODE_BG, 12, context, Theme.STROKE)
                }
                box.addView(tv, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        scrollOf(box)
    }

    private fun localIndex(): String? {
        val keys = virtual.keys
        return keys.firstOrNull { it == "index.html" } ?: keys.filter { it.endsWith("/index.html") }.minByOrNull { it.length }
            ?: keys.firstOrNull { it.endsWith(".html") }
    }

    private fun mime(p: String) = when (p.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        else -> "text/plain"
    }

    private fun messagePage(text: String) = "<html><body style='margin:0;background:#${String.format("%06x", Theme.BG and 0xFFFFFF)};color:#${String.format("%06x", Theme.MUTED and 0xFFFFFF)};font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'><div style='padding:32px;font-size:15px;line-height:1.5'>$text</div></body></html>"

    private fun showLocal(v: WebView) {
        val idx = localIndex()
        if (idx == null) {
            modeView?.text = ""
            v.loadDataWithBaseURL(null, messagePage("Превью недоступно: в песочнице не запущен веб-сервер.<br><br>Нажмите «Запустить» — агент поднимет сервер превью.<br>Или откройте вкладку «Файлы» и получите файлы — тогда страница откроется прямо в приложении."), "text/html", "utf-8", null)
        } else {
            localMode = true
            modeView?.text = "локально, из файлов задачи"
            v.loadUrl("https://$LOCAL/$idx")
        }
    }

    private fun renderPreview() {
        val id = taskId
        body.removeAllViews()
        if (id == null) {
            body.addView(empty("Превью доступно внутри задачи."), FrameLayout.LayoutParams(-1, -1))
            return
        }
        val url = "https://$id.preview.qwenlm.io/"
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        bar.addView(TextView(context).apply {
            text = "Обновить"
            setTextColor(Theme.ACCENT)
            textSize = 13f
            setPadding(0, dp(6), dp(16), dp(6))
            setOnClickListener {
                localMode = false
                modeView?.text = ""
                web?.loadUrl(url)
            }
        })
        bar.addView(TextView(context).apply {
            text = "В браузере"
            setTextColor(Theme.ACCENT)
            textSize = 13f
            setPadding(0, dp(6), dp(16), dp(6))
            setOnClickListener { onOpenBrowser(url) }
        })
        bar.addView(TextView(context).apply {
            text = "Запустить"
            setTextColor(Theme.ACCENT)
            textSize = 13f
            setPadding(0, dp(6), dp(16), dp(6))
            setOnClickListener { onStartPreview() }
        })
        modeView = TextView(context).apply {
            setTextColor(Theme.MUTED)
            textSize = 12f
            maxLines = 1
            text = if (localMode) "локально, из файлов задачи" else ""
        }
        bar.addView(modeView, LayoutParams(0, -2, 1f))
        col.addView(bar, LayoutParams(-1, -2))
        if (web == null || webFor != id) {
            web?.destroy()
            web = WebView(context).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpError(v: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceResponse) {
                        if (r.isForMainFrame && e.statusCode >= 400 && r.url.host != LOCAL) v.post { showLocal(v) }
                    }

                    override fun onReceivedError(v: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                        if (r.isForMainFrame && r.url.host != LOCAL) v.post { showLocal(v) }
                    }

                    override fun shouldInterceptRequest(v: WebView, r: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        if (r.url.host != LOCAL) return null
                        val path = (r.url.path ?: "").trimStart('/')
                        val files = virtual
                        val root = (localIndex() ?: "").substringBeforeLast('/', "")
                        val key = files.keys.firstOrNull { it == path }
                            ?: files.keys.firstOrNull { root.isNotEmpty() && it == "$root/$path" }
                            ?: files.keys.firstOrNull { it.endsWith("/$path") }
                        val data = if (key != null) files[key] else null
                        return if (data != null) {
                            android.webkit.WebResourceResponse(mime(key!!), "utf-8", java.io.ByteArrayInputStream(data))
                        } else {
                            android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not Found", HashMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
                loadUrl(url)
            }
            webFor = id
        } else if (localMode) {
            web?.reload()
        }
        (web!!.parent as? android.view.ViewGroup)?.removeView(web)
        col.addView(web, LayoutParams(-1, 0, 1f))
        body.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    private companion object {
        const val LOCAL = "local.preview"
    }

    fun destroy() {
        web?.destroy()
        web = null
    }
}
