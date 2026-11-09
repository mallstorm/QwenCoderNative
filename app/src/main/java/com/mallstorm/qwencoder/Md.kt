package com.mallstorm.qwencoder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Theme {
    val BG = Color.rgb(13, 14, 19)
    val SURFACE = Color.rgb(22, 24, 33)
    val SURFACE2 = Color.rgb(30, 33, 45)
    val STROKE = Color.rgb(42, 46, 61)
    val TEXT = Color.rgb(232, 234, 240)
    val MUTED = Color.rgb(138, 145, 163)
    val ACCENT = Color.rgb(139, 92, 246)
    val ACCENT2 = Color.rgb(59, 130, 246)
    val USER1 = Color.rgb(104, 76, 222)
    val USER2 = Color.rgb(54, 100, 220)
    val GREEN = Color.rgb(74, 222, 128)
    val AMBER = Color.rgb(251, 191, 36)
    val RED = Color.rgb(248, 113, 113)
    val CODE_BG = Color.rgb(10, 11, 15)
    val CODE_INLINE = Color.rgb(40, 44, 60)
}

object Md {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun round(color: Int, radiusDp: Int, ctx: Context, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(ctx, radiusDp.toFloat())
        if (stroke != 0) setStroke(dp(ctx, 1), stroke)
    }

    fun gradient(ctx: Context, c1: Int, c2: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            cornerRadius = dpf(ctx, radiusDp.toFloat())
        }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun circleGradient(c1: Int, c2: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            shape = GradientDrawable.OVAL
        }

    fun rippleCircle(): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(48, 255, 255, 255)), null, circle(Color.WHITE))

    fun rippleRect(content: Drawable?): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(36, 255, 255, 255)), content, GradientDrawable().apply { setColor(Color.WHITE) })

    fun logoView(ctx: Context, sizeDp: Int): ImageView = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_logo)
        background = gradient(ctx, Theme.ACCENT, Theme.ACCENT2, (sizeDp * 0.3f).toInt())
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp(ctx, (sizeDp * 0.16f).toInt())
        setPadding(p, p, p, p)
    }

    fun copy(ctx: Context, s: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", s))
        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    // ---------- markdown ----------

    private val headRe = Regex("^(#{1,6})\\s+(.*)$")
    private val hrRe = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val bulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val orderedRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val tableSepRe = Regex("^\\|?[\\s:|-]+\\|?$")

    fun render(ctx: Context, parent: LinearLayout, text: String) {
        parent.removeAllViews()
        val lines = text.replace("\r", "").split("\n")
        val para = StringBuilder()
        fun flush() {
            val s = para.toString().trim()
            para.setLength(0)
            if (s.isNotEmpty()) parent.addView(paragraph(ctx, s))
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```")) {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++
                parent.addView(codeBlock(ctx, code.toString().trimEnd('\n'), lang))
                continue
            }
            val h = headRe.find(t)
            if (h != null) {
                flush()
                parent.addView(heading(ctx, h.groupValues[1].length, h.groupValues[2]))
                i++
                continue
            }
            if (hrRe.matches(t)) {
                flush()
                parent.addView(View(ctx).apply { setBackgroundColor(Theme.STROKE) },
                    LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 12) })
                i++
                continue
            }
            if (t.startsWith(">")) {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    q.append(lines[i].trim().removePrefix(">").trim()).append('\n')
                    i++
                }
                parent.addView(quote(ctx, q.toString().trim()))
                continue
            }
            if (t.startsWith("|")) {
                flush()
                val rows = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val r = lines[i].trim()
                    if (!tableSepRe.matches(r)) rows.add(r)
                    i++
                }
                parent.addView(table(ctx, rows))
                continue
            }
            val b = bulletRe.find(line)
            val o = orderedRe.find(line)
            if (b != null || o != null) {
                flush()
                val indent = ((b ?: o)!!.groupValues[1].length / 2).coerceAtMost(4)
                val marker = if (b != null) "•" else o!!.groupValues[2] + "."
                val content = if (b != null) b.groupValues[2] else o!!.groupValues[3]
                parent.addView(listItem(ctx, marker, content, indent))
                i++
                continue
            }
            if (t.isEmpty()) flush() else para.append(line).append('\n')
            i++
        }
        flush()
    }

    private fun textView(ctx: Context, sizeSp: Float, bottomDp: Int): TextView = TextView(ctx).apply {
        setTextColor(Theme.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.22f)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, bottomDp) }
    }

    private fun setRich(tv: TextView, sp: SpannableStringBuilder) {
        tv.text = sp
        tv.setLinkTextColor(Theme.ACCENT2)
        if (sp.getSpans(0, sp.length, URLSpan::class.java).isNotEmpty()) {
            tv.movementMethod = LinkMovementMethod.getInstance()
        } else {
            tv.setTextIsSelectable(true)
        }
    }

    private fun heading(ctx: Context, level: Int, s: String): View {
        val size = when (level) { 1 -> 21f; 2 -> 19f; 3 -> 17f; else -> 16f }
        return textView(ctx, size, 8).apply {
            typeface = Typeface.DEFAULT_BOLD
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(ctx, 6)
            setRich(this, richText(s))
        }
    }

    private fun paragraph(ctx: Context, s: String): View =
        textView(ctx, 15.5f, 10).apply { setRich(this, richText(s)) }

    private fun listItem(ctx: Context, marker: String, content: String, indent: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(ctx, 5)
                leftMargin = dp(ctx, 4 + indent * 16)
            }
        }
        row.addView(TextView(ctx).apply {
            text = marker
            setTextColor(Theme.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            minWidth = dp(ctx, 20)
        })
        row.addView(textView(ctx, 15.5f, 0).apply {
            setRich(this, richText(content))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        return row
    }

    private fun quote(ctx: Context, s: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 10) }
        }
        row.addView(View(ctx).apply { setBackgroundColor(Theme.ACCENT) }, LinearLayout.LayoutParams(dp(ctx, 3), -1))
        row.addView(textView(ctx, 15f, 0).apply {
            setTextColor(Theme.MUTED)
            setPadding(dp(ctx, 12), 0, 0, 0)
            setRich(this, richText(s))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        return row
    }

    private fun table(ctx: Context, rows: List<String>): View {
        val hs = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            background = round(Theme.SURFACE, 10, ctx, Theme.STROKE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 10) }
        }
        val text = rows.joinToString("\n") { r ->
            r.trim().trim('|').split("|").joinToString("  │  ") { it.trim() }
        }
        hs.addView(TextView(ctx).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Theme.TEXT)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            setTextIsSelectable(true)
        })
        return hs
    }

    private val inlineRe = Regex("""`([^`\n]+)`|\*\*(.+?)\*\*|\[([^\]\n]+)]\((https?://[^)\s]+)\)|(?<![*\w])\*([^*\n]+)\*(?![*\w])|(?<!\w)_([^_\n]+)_(?!\w)""")

    fun richText(s: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        var last = 0
        for (m in inlineRe.findAll(s)) {
            sb.append(s, last, m.range.first)
            val start = sb.length
            val g = m.groups
            when {
                g[1] != null -> {
                    sb.append(g[1]!!.value)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(Theme.CODE_INLINE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Color.rgb(196, 181, 253)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.92f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[2] != null -> {
                    sb.append(g[2]!!.value)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[3] != null -> {
                    sb.append(g[3]!!.value)
                    sb.setSpan(URLSpan(g[4]!!.value), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[5] != null -> {
                    sb.append(g[5]!!.value)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    sb.append(g[6]!!.value)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
        return sb
    }

    // ---------- code ----------

    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "for", "while", "when", "in", "is", "as",
        "null", "true", "false", "import", "package", "def", "from", "with", "try", "catch", "except", "finally", "throw",
        "throws", "new", "this", "self", "const", "let", "function", "async", "await", "export", "default", "public",
        "private", "protected", "static", "final", "void", "int", "long", "float", "double", "boolean", "bool", "string",
        "override", "data", "enum", "struct", "impl", "pub", "use", "mod", "fn", "match", "type", "extends", "implements",
        "switch", "case", "break", "continue", "do", "lambda", "None", "True", "False", "then", "fi", "done", "elif",
        "pass", "yield", "raise", "assert", "global", "and", "or", "not", "sealed", "abstract", "suspend", "companion",
        "init", "constructor", "lateinit", "typealias", "namespace", "using", "func", "defer", "range", "echo"
    )
    private val HASH_LANGS = setOf("python", "py", "sh", "bash", "shell", "zsh", "yaml", "yml", "toml", "ruby", "rb", "dockerfile", "makefile", "ini", "conf", "r")
    private val C_COMMENT = Color.rgb(108, 122, 144)
    private val C_STRING = Color.rgb(165, 214, 167)
    private val C_NUMBER = Color.rgb(247, 140, 108)
    private val C_KEYWORD = Color.rgb(199, 146, 234)

    fun highlight(code: String, lang: String): CharSequence {
        val sb = SpannableStringBuilder(code)
        if (code.length > 9000) return sb
        val comment = if (lang.lowercase() in HASH_LANGS) "#[^\\n]*" else "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
        val re = Regex("""($comment)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`[^`]*`)|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_][A-Za-z0-9_]*)\b""")
        for (m in re.findAll(code)) {
            val color = when {
                m.groups[1] != null -> C_COMMENT
                m.groups[2] != null -> C_STRING
                m.groups[3] != null -> C_NUMBER
                else -> if (m.groupValues[4] in KEYWORDS) C_KEYWORD else 0
            }
            if (color != 0) {
                sb.setSpan(ForegroundColorSpan(color), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    fun codeBlock(ctx: Context, code: String, lang: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Theme.CODE_BG, 12, ctx, Theme.STROKE)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 12) }
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        head.addView(TextView(ctx).apply {
            text = if (lang.isEmpty()) "code" else lang
            setTextColor(Theme.MUTED)
            textSize = 12f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val copyBtn = TextView(ctx).apply {
            text = "Копировать"
            setTextColor(Theme.ACCENT2)
            textSize = 12f
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        }
        copyBtn.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("code", code))
            copyBtn.text = "Скопировано ✓"
            Handler(Looper.getMainLooper()).postDelayed({ copyBtn.text = "Копировать" }, 1600)
        }
        head.addView(copyBtn)
        box.addView(head)
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(TextView(ctx).apply {
            text = highlight(code, lang)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.rgb(214, 222, 235))
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
            setTextIsSelectable(true)
        })
        box.addView(hs)
        return box
    }
}
