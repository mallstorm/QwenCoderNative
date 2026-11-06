package com.mallstorm.qwencoder

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Optional in-app login: opens the Qwen sign-in page and waits for the refresh_token cookie.
 * Google sign-in is often blocked inside WebView; pasting the token from a desktop browser is the reliable path.
 */
class WebLoginActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())

    private val check = object : Runnable {
        override fun run() {
            val c = CookieManager.getInstance().getCookie("https://coder.qwen.ai") ?: ""
            val rt = Regex("(?:^|;\\s*)refresh_token=([^;]+)").find(c)?.groupValues?.get(1)
            if (!rt.isNullOrEmpty() && rt.startsWith("eyJ")) {
                setResult(RESULT_OK, Intent().putExtra("refresh", rt))
                finish()
                return
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.SURFACE
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().removeAllCookies(null)
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(20, 22, 27))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = Api.UA
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl("https://coder.qwen.ai/auth")
        ui.postDelayed(check, 1500)
    }

    override fun onDestroy() {
        ui.removeCallbacks(check)
        web.destroy()
        super.onDestroy()
    }
}
