package com.tetra.bot

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

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

        setContentView(R.layout.activity_calibration)

        val view = findViewById<CalibrationView>(R.id.calib_view)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.cancel_btn).setOnClickListener { finish() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.save_btn).setOnClickListener {
            com.tetra.bot.core.Prefs.roi = view.roi()
            com.tetra.bot.core.BotState.configVersion.value += 1
            com.tetra.bot.core.BotState.lastBoard.value = null
            finish()
        }
    }
}