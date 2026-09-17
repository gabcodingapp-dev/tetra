package com.tetra.bot

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.tetra.bot.core.BotState
import com.tetra.bot.core.Prefs

/**
 * Translucent full-screen alignment screen. The real game stays visible
 * underneath; the user frames the 4x4 board with the square.
 */
class CalibrationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        hideSystemBars()

        setContentView(R.layout.activity_calibration)

        val view = findViewById<CalibrationView>(R.id.calib_view)
        findViewById<MaterialButton>(R.id.auto_btn).setOnClickListener {
            // The calibration screen would show up in the screenshot, so ask the
            // bot to detect while we hide, then reopen so the box snaps to the result.
            finish()
            BotState.autoDetectRequested.value = true
            BotState.status.value = "Auto-aligning…"
        }
        findViewById<MaterialButton>(R.id.cancel_btn).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.save_btn).setOnClickListener {
            Prefs.roi = view.roi()
            BotState.configVersion.value += 1
            BotState.lastBoard.value = null
            finish()
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }
}