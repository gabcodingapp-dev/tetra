package com.tetra.bot

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.tetra.bot.bot.BotAccessibilityService
import com.tetra.bot.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var overlayBtn: MaterialButton
    private lateinit var a11yBtn: MaterialButton
    private lateinit var startBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = findViewById(R.id.overlay_status)
        a11yStatus = findViewById(R.id.a11y_status)
        overlayBtn = findViewById(R.id.overlay_btn)
        a11yBtn = findViewById(R.id.a11y_btn)
        startBtn = findViewById(R.id.start_btn)

        overlayBtn.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        a11yBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startBtn.setOnClickListener { OverlayService.start(this) }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isAccessibilityEnabled()

        overlayStatus.text = if (overlayOk) "Granted ✓ — the bubble can float over apps"
        else "Not granted — tap below, enable Tetra"
        overlayStatus.setTextColor(getColor(if (overlayOk) R.color.tetra_good else R.color.tetra_bad))

        a11yStatus.text = if (a11yOk) "Enabled ✓ — Tetra can read the screen and swipe"
        else "Not enabled — Tap below, enable 'Tetra'"
        a11yStatus.setTextColor(getColor(if (a11yOk) R.color.tetra_good else R.color.tetra_bad))

        startBtn.isEnabled = overlayOk && a11yOk
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, BotAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }
}