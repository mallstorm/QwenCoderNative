package com.mallstorm.qwencoder

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Qwen runs in Chrome's authenticated browser context.
 * This is a trampoline, not a second visible app window.
 * Once Chrome opens, remove the launcher Activity from its task.
 */
class MainActivity : Activity() {
    private val qwenUrl = Uri.parse("https://coder.qwen.ai/")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        openQwen()
    }

    private fun openQwen() {
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(false)
            .setUrlBarHidingEnabled(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .build()

        try {
            // Reuse Chrome's cookies for Qwen and Google OAuth.
            tab.intent.setPackage("com.android.chrome")
            tab.launchUrl(this, qwenUrl)
            finish()
            return
        } catch (_: Exception) {
            // Another browser may support Custom Tabs even if Chrome is absent.
            try {
                tab.intent.setPackage(null)
                tab.launchUrl(this, qwenUrl)
                finish()
                return
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, qwenUrl))
                    finish()
                    return
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, "Установите браузер Chrome", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
}
