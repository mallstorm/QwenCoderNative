package com.mallstorm.qwencoder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast

class MainActivity : Activity() {
 private lateinit var web: WebView
 private lateinit var mic: ImageButton
 private lateinit var progress: ProgressBar
 private var recognizer: SpeechRecognizer? = null
 private var chooser: ValueCallback<Array<Uri>>? = null
 private var listening = false

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor = Color.rgb(24,24,24)
  window.navigationBarColor = Color.rgb(24,24,24)
  window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  val d = resources.displayMetrics.density
  val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(28,28,28)) }
  web = WebView(this)
  root.addView(web, FrameLayout.LayoutParams(-1,-1))
  progress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; visibility=View.GONE }
  root.addView(progress,FrameLayout.LayoutParams(-1,6))
  mic = ImageButton(this).apply {
   setImageResource(android.R.drawable.ic_btn_speak_now)
   setColorFilter(Color.WHITE)
   background=GradientDrawable().apply { setColor(Color.rgb(56,65,90)); cornerRadius=32*d }
   contentDescription="Голосовой ввод"
   setOnClickListener { if(listening) recognizer?.stopListening() else startDictation() }
  }
  root.addView(mic,FrameLayout.LayoutParams((42*d).toInt(),(42*d).toInt(),Gravity.RIGHT or Gravity.BOTTOM).apply {
   rightMargin=(65*d).toInt(); bottomMargin=(25*d).toInt()
  })
  setContentView(root)
  CookieManager.getInstance().setAcceptCookie(true)
  CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
  web.settings.apply {
   javaScriptEnabled=true; domStorageEnabled=true; databaseEnabled=true
   allowFileAccess=false; allowContentAccess=true
   builtInZoomControls=false; displayZoomControls=false
   useWideViewPort=true; loadWithOverviewMode=true
   mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
  }
  web.webViewClient=object:WebViewClient(){
   override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean {
    val uri=request?.url ?: return false
    if(uri.scheme!="https") { if(uri.scheme=="mailto"||uri.scheme=="tel") external(uri);return true }
    val host=uri.host.orEmpty().lowercase()
    if(host=="accounts.google.com" || host=="github.com" && (uri.path.orEmpty().startsWith("/login") || uri.path.orEmpty().startsWith("/oauth"))) {
     external(uri);return true
    }
    return false
   }
   override fun onReceivedError(view:WebView?,request:WebResourceRequest?,error:WebResourceError?) {
    if(request?.isForMainFrame==true) Toast.makeText(this@MainActivity,"Ошибка сети. Проверьте VPN.",Toast.LENGTH_LONG).show()
   }
  }
  web.webChromeClient=object:WebChromeClient(){
   override fun onProgressChanged(view:WebView?,p:Int){ progress.progress=p;progress.visibility=if(p==100)View.GONE else View.VISIBLE }
   override fun onShowFileChooser(view:WebView?,cb:ValueCallback<Array<Uri>>?,params:FileChooserParams?):Boolean {
    chooser?.onReceiveValue(null);chooser=cb
    return try { startActivityForResult(params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type="*/*" },1001);true }
    catch(e:Exception){chooser=null;false}
   }
  }
  if(savedInstanceState==null) web.loadUrl("https://coder.qwen.ai/") else web.restoreState(savedInstanceState)
 }
 private fun startDictation() {
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
   requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),2001);return
  }
  if(!SpeechRecognizer.isRecognitionAvailable(this)){
   Toast.makeText(this,"Распознавание речи недоступно",Toast.LENGTH_LONG).show();return
  }
  recognizer?.destroy()
  recognizer=SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
   sr.setRecognitionListener(object:RecognitionListener {
    override fun onReadyForSpeech(params:Bundle?){}
    override fun onBeginningOfSpeech(){}
    override fun onRmsChanged(rmsdB:Float){}
    override fun onBufferReceived(buffer:ByteArray?){}
    override fun onEndOfSpeech(){}
    override fun onError(error:Int){ finishSpeech();Toast.makeText(this@MainActivity,"Ошибка диктовки: $error",Toast.LENGTH_SHORT).show() }
    override fun onResults(results:Bundle?){
     results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { insertText(it) }
     finishSpeech()
    }
    override fun onPartialResults(partialResults:Bundle?){}
    override fun onEvent(eventType:Int,params:Bundle?){}
   })
  }
  listening=true;mic.setColorFilter(Color.RED)
  recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
   putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
   putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU")
  })
 }
 private fun finishSpeech(){listening=false;mic.setColorFilter(Color.WHITE);recognizer?.destroy();recognizer=null}
 private fun insertText(words:String){
  val encoded=android.util.Base64.encodeToString(words.toByteArray(Charsets.UTF_8),android.util.Base64.NO_WRAP)
  web.evaluateJavascript("""(function(){
   const text=decodeURIComponent(escape(atob('$encoded')));
   const active=document.activeElement;
   const els=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"],input[type="text"]')];
   const el=els.includes(active)?active:els.filter(e=>e.getClientRects().length).pop();
   if(!el)return false;
   el.focus();
   if(el.isContentEditable){
    const sel=window.getSelection(),range=document.createRange();
    range.selectNodeContents(el);range.collapse(false);sel.removeAllRanges();sel.addRange(range);
    document.execCommand('insertText',false,(el.textContent?.trim()?' ':'')+text);
    el.dispatchEvent(new InputEvent('input',{bubbles:true,data:text,inputType:'insertText'}));
   }else{
    const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto,'value').set.call(el,el.value+(el.value?' ':'')+text);
    el.dispatchEvent(new Event('input',{bubbles:true}));
   }
   return true;
  })();""".trimIndent()) { if(it!="true")Toast.makeText(this,"Поле ввода не найдено",Toast.LENGTH_SHORT).show() }
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==2001 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)startDictation()
 }
 private fun external(uri:Uri){try{startActivity(Intent(Intent.ACTION_VIEW,uri))}catch(_:Exception){}}
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==1001){chooser?.onReceiveValue(if(resultCode==RESULT_OK)WebChromeClient.FileChooserParams.parseResult(resultCode,data) else null);chooser=null}
 }
 override fun onSaveInstanceState(outState:Bundle){web.saveState(outState);super.onSaveInstanceState(outState)}
 @Deprecated("Legacy back handling") override fun onBackPressed(){if(web.canGoBack())web.goBack() else super.onBackPressed()}
 override fun onDestroy(){recognizer?.destroy();web.destroy();super.onDestroy()}
}