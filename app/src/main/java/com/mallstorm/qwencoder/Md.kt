package com.mallstorm.qwencoder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Theme {
    val BG = Color.rgb(20, 22, 27)
    val SURFACE = Color.rgb(30, 34, 42)
    val USER_BUBBLE = Color.rgb(43, 58, 85)
    val TEXT = Color.rgb(230, 232, 236)
    val MUTED = Color.rgb(139, 147, 161)
    val ACCENT = Color.rgb(91, 141, 239)
    val CODE_BG = Color.rgb(15, 17, 21)
    val CODE_INLINE = Color.rgb(45, 50, 62)
}

object Md {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun round(color: Int, radiusDp: Int, ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
    }

    fun copy(ctx: Context, s: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", s))
        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun render(ctx: Context, parent: LinearLayout, text: String) {
        parent.removeAllViews()
        val buf = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        var lang = ""
        val headRe = Regex("^#{1,6}\\s+")

        fun flush() {
            val s = buf.toString().trim('\n')
            buf.setLength(0)
            if (s.isNotEmpty()) parent.addView(paragraph(ctx, s))
        }

        for (line in text.split("\n")) {
            if (line.trimStart().startsWith("```")) {
                if (!inCode) {
                    flush()
                    inCode = true
                    lang = line.trim().removePrefix("```").trim()
                    code.setLength(0)
                } else {
                    parent.addView(codeBlock(ctx, code.toString().trimEnd('\n'), lang))
                    inCode = false
                }
                continue
            }
            if (inCode) {
                code.append(line).append('\n')
                continue
            }
            if (headRe.containsMatchIn(line)) {
                flush()
                parent.addView(heading(ctx, line.replace(headRe, "")))
                continue
            }
            buf.append(line).append('\n')
        }
        if (inCode) parent.addView(codeBlock(ctx, code.toString().trimEnd('\n'), lang))
        flush()
    }

    private fun baseTv(ctx: Context, sizeSp: Float): TextView = TextView(ctx).apply {
        setTextColor(Theme.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.18f)
        setTextIsSelectable(true)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 8) }
    }

    private fun heading(ctx: Context, s: String): View = baseTv(ctx, 18f).apply {
        typeface = Typeface.DEFAULT_BOLD
        text = inline(s)
    }

    private fun paragraph(ctx: Context, s: String): View {
        val converted = s.lines().joinToString("\n") { l ->
            val t = l.trimStart()
            if (t.startsWith("- ") || t.startsWith("* ")) "  • " + t.substring(2) else l
        }
        return baseTv(ctx, 15.5f).apply { text = inline(converted) }
    }

    private val inlineRe = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*")

    private fun inline(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        var last = 0
        for (m in inlineRe.findAll(s)) {
            sb.append(s, last, m.range.first)
            val start = sb.length
            val code = m.groups[1]?.value
            if (code != null) {
                sb.append(code)
                sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(Theme.CODE_INLINE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.92f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.append(m.groups[2]?.value ?: "")
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
        return sb
    }

    private fun codeBlock(ctx: Context, code: String, lang: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Theme.CODE_BG, 10, ctx)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 10) }
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 4))
        }
        head.addView(TextView(ctx).apply {
            text = if (lang.isEmpty()) "code" else lang
            setTextColor(Theme.MUTED)
            textSize = 12f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(ctx).apply {
            text = "Копировать"
            setTextColor(Theme.ACCENT)
            textSize = 12f
            setPadding(dp(ctx, 8), dp(ctx, 4), 0, dp(ctx, 4))
            setOnClickListener { copy(ctx, code) }
        })
        box.addView(head)
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(TextView(ctx).apply {
            text = code
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(Color.rgb(214, 222, 235))
            setPadding(dp(ctx, 12), dp(ctx, 2), dp(ctx, 12), dp(ctx, 12))
            setTextIsSelectable(true)
        })
        box.addView(hs)
        return box
    }
}
