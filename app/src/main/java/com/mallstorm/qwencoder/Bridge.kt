package com.mallstorm.qwencoder

import android.content.Context
import android.webkit.WebView
import org.json.JSONTokener

object Bridge {
    private var js: String? = null

    fun inject(ctx: Context, web: WebView, cfgJson: String) {
        val code = js ?: ctx.assets.open("qc_bridge.js").bufferedReader().use { it.readText() }.also { js = it }
        web.evaluateJavascript("window.__QC_CFG=" + cfgJson + ";\n" + code, null)
    }

    /** evaluateJavascript returns a JSON-encoded value; for string results this unwraps it. */
    fun unquote(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            JSONTokener(raw).nextValue() as? String
        } catch (_: Exception) {
            null
        }
    }
}
