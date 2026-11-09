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
    private val onOpenBrowser: (String) -> Unit
) : LinearLayout(ctx) {

    private var tab = 0
    private var files: List<FileChange> = emptyList()
    private var shells: List<Step> = emptyList()
    private var taskId: String? = null
    private var commit = ""
    private var sig = ""
    private var active = false
    private val expanded = HashSet<String>()
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
        listOf("Изменения", "Терминал", "Превью").forEachIndexed { i, n ->
            val t = TextView(ctx).apply {
                text = n
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { tab = i; styleTabs(); render() }
            }
            tabViews.add(t)
            tabs.addView(t, LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
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
        var cm = ""
        for (m in msgs) {
            if (m.role != "assistant") continue
            for (s in m.steps) {
                if (s.type.equals("Shell", true)) sh[m.id + s.id] = s
            }
            for (c in m.files) f[c.path] = c
            if (m.commit.isNotEmpty()) cm = m.commit
        }
        // in-progress edits (known from tool-call args before the server reports file_list)
        val last = msgs.lastOrNull()
        val stepsNow = if (last != null && last.role == "assistant") last.steps else emptyList()
        for (c in changes(stepsNow + live)) if (!f.containsKey(c.path)) f[c.path] = c
        files = f.values.toList()
        shells = sh.values.toList()
        commit = cm
        taskId = id
        val s = "$id|${files.size}|${files.sumOf { it.newText.length + it.oldText.length }}|${shells.size}|${shells.lastOrNull()?.log?.length}|${shells.lastOrNull()?.status}"
        if (s == sig) return
        sig = s
        titleView.text = if (files.isEmpty()) "Код" else "Код · ${files.size} ${if (files.size == 1) "файл" else "файлов"}"
        if (active) render()
    }

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
            1 -> renderTerminal()
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
            box.addView(empty("Пока нет изменений.\nКогда агент поменяет файлы, их diff появится здесь."))
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
                    sb.setSpan(ForegroundColorSpan(Color.rgb(187, 247, 208)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                '-' -> {
                    sb.setSpan(BackgroundColorSpan(Color.argb(60, 248, 113, 113)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Color.rgb(254, 202, 202)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                val out = s.log.trim()
                if (out.isNotEmpty()) {
                    sb.append("\n")
                    val a = sb.length
                    sb.append(if (out.length > 6000) out.take(6000) + "\n…" else out)
                    sb.setSpan(ForegroundColorSpan(Color.rgb(205, 210, 222)), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            setOnClickListener { web?.reload() }
        })
        bar.addView(TextView(context).apply {
            text = "В браузере"
            setTextColor(Theme.ACCENT)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onOpenBrowser(url) }
        })
        col.addView(bar, LayoutParams(-1, -2))
        if (web == null || webFor != id) {
            web?.destroy()
            web = WebView(context).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
            }
            webFor = id
        }
        (web!!.parent as? android.view.ViewGroup)?.removeView(web)
        col.addView(web, LayoutParams(-1, 0, 1f))
        body.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    fun destroy() {
        web?.destroy()
        web = null
    }
}
