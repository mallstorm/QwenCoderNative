package com.mallstorm.qwencoder

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.graphics.Color
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : Activity() {
 private val url = "https://coder.qwen.ai/"
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
  val layout = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(36, 32, 36, 32)
   setBackgroundColor(Color.rgb(25, 27, 32))
  }
  val title = TextView(this).apply {
   text = "Qwen Coder · Desktop"
   textSize = 22f
   setTextColor(Color.WHITE)
  }
  val note = TextView(this).apply {
   text = "Авторизация Google и Qwen работают в одной сессии браузера. После входа возвращайтесь в приложение через системную навигацию."
   textSize = 15f
   setTextColor(Color.LTGRAY)
  }
  val open = Button(this).apply {
   text = "Открыть Qwen Coder"
   setOnClickListener { openQwen() }
  }
  layout.addView(title)
  layout.addView(note)
  layout.addView(open)
  setContentView(layout)
  if(savedInstanceState == null) openQwen()
 }
 private fun openQwen() {
  try {
   val tabs = CustomTabsIntent.Builder()
    .setShowTitle(false)
    .setUrlBarHidingEnabled(true)
    .build()
   tabs.intent.setPackage("com.android.chrome")
   tabs.launchUrl(this, Uri.parse(url))
  } catch (_: Exception) {
   try {
    val tabs = CustomTabsIntent.Builder().setShowTitle(false).build()
    tabs.launchUrl(this, Uri.parse(url))
   } catch (_: Exception) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
   }
  }
 }
}