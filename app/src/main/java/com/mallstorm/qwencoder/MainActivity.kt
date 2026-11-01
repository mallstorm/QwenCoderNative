package com.mallstorm.qwencoder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var mic: ImageButton
    private lateinit var progress: ProgressBar
    private var recognizer: SpeechRecognizer? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var listening = false

    private val home = "https://coder.qwen.ai/"
    private val desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.statusBarColor = Color.rgb(20, 22, 27)
        window.navigationBarColor = Color.rgb(20, 22, 27)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(20, 22, 27)) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(-1, (3 * density).toInt(), Gravity.TOP))

        val menu = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setColorFilter(Color.WHITE)
            contentDescription = "Меню Qwen Coder Native"
            background = rounded(Color.rgb(36, 41, 52), density)
            alpha = 0.92f
            setOnClickListener { showMenu() }
        }
        root.addView(menu, FrameLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt(), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = (8 * density).toInt()
            topMargin = (8 * density).toInt()
        })

        mic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            contentDescription = "Голосовая диктовка"
            background = rounded(Color.rgb(52, 77, 111), density)
            elevation = 8 * density
        }
        val micParams = FrameLayout.LayoutParams((46 * density).toInt(), (46 * density).toInt(), Gravity.RIGHT or Gravity.BOTTOM).apply {
            rightMargin = (72 * density).toInt()
            bottomMargin = (22 * density).toInt()
        }
        root.addView(mic, micParams)
        // Button can be moved so that it never covers Qwen's send control.
        var startX = 0f
        var startY = 0f
        var initialRight = 0
        var initialBottom = 0
        var moved = false
        mic.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialRight = micParams.rightMargin
                    initialBottom = micParams.bottomMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) > 8 * density || abs(dy) > 8 * density) moved = true
                    if (moved) {
                        micParams.rightMargin = (initialRight - dx).toInt().coerceIn(0, (root.width - mic.width).coerceAtLeast(0))
                        micParams.bottomMargin = (initialBottom - dy).toInt().coerceIn(0, (root.height - mic.height).coerceAtLeast(0))
                        mic.layoutParams = micParams
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (listening) recognizer?.stopListening() else startDictation()
                    }
                    true
                }
                else -> true
            }
        }
        setContentView(root)
        configureWebView()
        if (savedInstanceState == null) web.loadUrl(home) else web.restoreState(savedInstanceState)
    }

    private fun rounded(color: Int, density: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 16 * density
    }

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
            supportZoom()
            textZoom = 100
            userAgentString = desktopUA
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
                if (url?.startsWith("https://coder.qwen.ai/") == true) {
                    // Use a wide CSS viewport, independently of Android screen dimensions.
                    view?.evaluateJavascript(
                        """(function(){var m=document.querySelector('meta[name="viewport"]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.setAttribute('content','width=1280, initial-scale=0.65, minimum-scale=0.25, maximum-scale=3, user-scalable=yes');return true;})();""",
                        null
                    )
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
                progress.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
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

    private fun showMenu() {
        val choices = arrayOf("Qwen Coder — главная", "Войти по email / коду", "Назад", "Обновить страницу", "Войти через Google в Chrome")
        AlertDialog.Builder(this)
            .setTitle("Qwen Coder Native")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> web.loadUrl(home)
                    1 -> web.loadUrl("https://coder.qwen.ai/auth")
                    2 -> if (web.canGoBack()) web.goBack()
                    3 -> web.reload()
                    4 -> showGoogleLoginNotice()
                }
            }.show()
    }

    private fun showGoogleLoginNotice() {
        AlertDialog.Builder(this)
            .setTitle("Вход Google")
            .setMessage("Google-авторизация в Android WebView может блокироваться. В Chrome она уже работает, но Chrome не передаёт свою сессию встроенному WebView. Для нативного интерфейса попробуйте вход по email с кодом подтверждения.")
            .setPositiveButton("Войти по email") { _, _ -> web.loadUrl("https://coder.qwen.ai/auth") }
            .setNeutralButton("Открыть Chrome") { _, _ -> openChrome() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openChrome() {
        // Keep Google's OAuth flow entirely inside one Chrome session.
        try {
            val tab = CustomTabsIntent.Builder().setShowTitle(false).setUrlBarHidingEnabled(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
            tab.intent.setPackage("com.android.chrome")
            tab.launchUrl(this, Uri.parse(home))
            finish()
        } catch (_: Exception) {
            try {
                launchExternal(Uri.parse(home))
                finish()
            } catch (_: Exception) {
                Toast.makeText(this, "Chrome недоступен", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchExternal(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {
            Toast.makeText(this, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
        }
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
                    if (!text.isNullOrBlank()) insertText(text)
                    finishDictation()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        listening = true
        mic.setColorFilter(Color.RED)
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }

    private fun finishDictation() {
        listening = false
        mic.setColorFilter(Color.WHITE)
        recognizer?.destroy()
        recognizer = null
    }

    private fun insertText(words: String) {
        val encoded = android.util.Base64.encodeToString(words.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        web.evaluateJavascript(
            """(function(){
              const bytes=Uint8Array.from(atob('$encoded'),c=>c.charCodeAt(0));
              const text=new TextDecoder('utf-8').decode(bytes);
              const selectors='textarea,[contenteditable="true"],[role="textbox"],input[type="text"]';
              const active=document.activeElement;
              const candidates=[...document.querySelectorAll(selectors)].filter(e=>e.getClientRects().length>0);
              const el=candidates.includes(active)?active:candidates[candidates.length-1];
              if(!el)return false;
              el.focus();
              if(el.isContentEditable){
                const sel=window.getSelection();
                if(!sel.rangeCount||!el.contains(sel.anchorNode)){
                  const range=document.createRange();range.selectNodeContents(el);range.collapse(false);
                  sel.removeAllRanges();sel.addRange(range);
                }
                document.execCommand('insertText',false,(el.textContent?.trim()?' ':'')+text);
                el.dispatchEvent(new InputEvent('input',{bubbles:true,data:text,inputType:'insertText'}));
              } else {
                const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                const setter=Object.getOwnPropertyDescriptor(proto,'value').set;
                const from=el.selectionStart??el.value.length;
                const to=el.selectionEnd??from;
                const value=el.value.slice(0,from)+(from>0?' ':'')+text+el.value.slice(to);
                setter.call(el,value);
                el.dispatchEvent(new Event('input',{bubbles:true}));
              }
              return true;
            })();""",
            { result ->
                if (result != "true") Toast.makeText(this, "Не найдено поле ввода Qwen", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDictation()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            fileChooser?.onReceiveValue(if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null)
            fileChooser = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy back handling")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        web.destroy()
        super.onDestroy()
    }
}
