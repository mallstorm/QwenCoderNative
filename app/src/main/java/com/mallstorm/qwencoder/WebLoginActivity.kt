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
            hideGoogle()
            ui.postDelayed(this, 1000)
        }
    }

    /** Google blocks sign-in inside WebView, so its buttons are hidden: GitHub, Apple and e-mail stay. */
    private fun hideGoogle() {
        web.evaluateJavascript(HIDE_JS, null)
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
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = hideGoogle()

            override fun shouldOverrideUrlLoading(view: WebView, r: android.webkit.WebResourceRequest): Boolean {
                val h = r.url.host.orEmpty()
                if (h == "accounts.google.com" || h.endsWith(".google.com") && r.url.path.orEmpty().contains("oauth")) {
                    android.widget.Toast.makeText(this@WebLoginActivity, "Вход через Google здесь недоступен — используйте GitHub или Apple", android.widget.Toast.LENGTH_LONG).show()
                    return true
                }
                return false
            }
        }
        setContentView(web)
        web.loadUrl("https://coder.qwen.ai/auth")
        ui.postDelayed(check, 1500)
    }

    companion object {
        private const val HIDE_JS = """(function(){
if(!window.__qcnStyle){var st=document.createElement('style');st.textContent='iframe[src*="accounts.google.com"],#credential_picker_container,#credential_picker_iframe,.g_id_signin,#g_id_onload{display:none!important}';(document.head||document.documentElement).appendChild(st);window.__qcnStyle=1;}
function h(){var els=document.querySelectorAll('button,a,[role=button],[class*=btn],[class*=Btn],[class*=button],[class*=Button],[class*=google],[class*=Google]');
for(var i=0;i<els.length;i++){var e=els[i];var t=(e.innerText||e.getAttribute('aria-label')||e.title||'').trim();
var cls=(typeof e.className==='string'?e.className:'')+' '+(e.id||'');
if((t.length>0&&t.length<60&&/google/i.test(t))||(/google/i.test(cls)&&t.length<60)){e.style.setProperty('display','none','important');}}}
h();if(!window.__qcnObs){window.__qcnObs=new MutationObserver(h);window.__qcnObs.observe(document.documentElement,{childList:true,subtree:true});}
})()"""
    }

    override fun onDestroy() {
        ui.removeCallbacks(check)
        web.destroy()
        super.onDestroy()
    }
}
