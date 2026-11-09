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
    var mode = "dark"
    var light = false
    var BG = 0; var SURFACE = 0; var SURFACE2 = 0; var STROKE = 0; var TEXT = 0; var MUTED = 0
    var ACCENT = 0; var ACCENT2 = 0; var USER1 = 0; var USER2 = 0
    var GREEN = 0; var AMBER = 0; var RED = 0
    var CODE_BG = 0; var CODE_INLINE = 0; var INLINE_FG = 0; var CODE_FG = 0
    var ADD_FG = 0; var DEL_FG = 0; var RIPPLE = 0
    var C_COMMENT = 0; var C_STRING = 0; var C_NUMBER = 0; var C_KEYWORD = 0

    fun apply(m: String) {
        mode = m
        light = m != "dark"
        fun c(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)
        when (m) {
            "light" -> {
                BG = c(250, 250, 252); SURFACE = c(255, 255, 255); SURFACE2 = c(238, 240, 246); STROKE = c(222, 226, 236)
                TEXT = c(24, 27, 36); MUTED = c(104, 112, 130)
                ACCENT = c(124, 77, 237); ACCENT2 = c(47, 123, 245); USER1 = ACCENT; USER2 = ACCENT2
            }
            "teal" -> {
                BG = c(238, 250, 249); SURFACE = c(255, 255, 255); SURFACE2 = c(220, 242, 240); STROKE = c(190, 224, 221)
                TEXT = c(14, 38, 42); MUTED = c(77, 118, 122)
                ACCENT = c(13, 148, 136); ACCENT2 = c(8, 145, 178); USER1 = c(13, 148, 136); USER2 = c(8, 145, 178)
            }
            else -> {
                BG = c(13, 14, 19); SURFACE = c(22, 24, 33); SURFACE2 = c(30, 33, 45); STROKE = c(42, 46, 61)
                TEXT = c(232, 234, 240); MUTED = c(138, 145, 163)
                ACCENT = c(139, 92, 246); ACCENT2 = c(59, 130, 246); USER1 = c(104, 76, 222); USER2 = c(54, 100, 220)
            }
        }
        if (light) {
            GREEN = c(22, 163, 74); AMBER = c(202, 138, 4); RED = c(220, 38, 38)
            CODE_BG = c(243, 245, 249); CODE_INLINE = c(231, 235, 243); INLINE_FG = if (m == "teal") c(15, 118, 110) else c(109, 40, 217)
            CODE_FG = c(40, 46, 60); ADD_FG = c(21, 101, 52); DEL_FG = c(153, 27, 27)
            RIPPLE = Color.argb(34, 0, 0, 0)
            C_COMMENT = c(100, 116, 139); C_STRING = c(21, 128, 61); C_NUMBER = c(194, 65, 12); C_KEYWORD = c(126, 34, 206)
        } else {
            GREEN = c(74, 222, 128); AMBER = c(251, 191, 36); RED = c(248, 113, 113)
            CODE_BG = c(10, 11, 15); CODE_INLINE = c(40, 44, 60); INLINE_FG = c(196, 181, 253); CODE_FG = c(205, 210, 222)
            ADD_FG = c(187, 247, 208); DEL_FG = c(254, 202, 202)
            RIPPLE = Color.argb(40, 255, 255, 255)
            C_COMMENT = c(108, 122, 144); C_STRING = c(165, 214, 167); C_NUMBER = c(247, 140, 108); C_KEYWORD = c(199, 146, 234)
        }
    }

    init { apply("dark") }
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
        RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), null, circle(Color.WHITE))

    fun rippleRect(content: Drawable?): Drawable =
        RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), content, GradientDrawable().apply { setColor(Color.WHITE) })

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
                    sb.setSpan(ForegroundColorSpan(Theme.INLINE_FG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    fun highlight(code: String, lang: String): CharSequence {
        val sb = SpannableStringBuilder(code)
        if (code.length > 9000) return sb
        val comment = if (lang.lowercase() in HASH_LANGS) "#[^\\n]*" else "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
        val re = Regex("""($comment)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`[^`]*`)|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_][A-Za-z0-9_]*)\b""")
        for (m in re.findAll(code)) {
            val color = when {
                m.groups[1] != null -> Theme.C_COMMENT
                m.groups[2] != null -> Theme.C_STRING
                m.groups[3] != null -> Theme.C_NUMBER
                else -> if (m.groupValues[4] in KEYWORDS) Theme.C_KEYWORD else 0
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
            setTextColor(Theme.CODE_FG)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
            setTextIsSelectable(true)
        })
        box.addView(hs)
        return box
    }
}
