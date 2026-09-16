package com.tetra.bot.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.tetra.bot.MainActivity
import com.tetra.bot.R
import com.tetra.bot.core.BotState
import com.tetra.bot.core.Prefs
import com.tetra.bot.engine.Solvers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var bubbleView: TextView? = null
    private var panelView: View? = null
    private var statusView: TextView? = null
    private var statsView: TextView? = null
    private var playBtn: TextView? = null
    private var speedBtn: TextView? = null
    private var strategyBtn: TextView? = null

    private var dragging = false
    private var startRawX = 0f
    private var startRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0

    private val speeds = listOf(1200L, 800L, 500L, 300L)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        BotState.overlayRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        addWindow()
        return START_STICKY
    }

    private fun addWindow() {
        if (root != null) return
        root = (LayoutInflater.from(this).inflate(R.layout.overlay_panel, null) as LinearLayout)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val dm = resources.displayMetrics
        lp.x = dm.widthPixels - dp(96)
        lp.y = dm.heightPixels / 3
        params = lp
        wm.addView(root, lp)

        bubbleView = root?.findViewById(R.id.bubble)
        panelView = root?.findViewById(R.id.panel)
        statusView = root?.findViewById(R.id.status_tv)
        statsView = root?.findViewById(R.id.stats_tv)
        playBtn = root?.findViewById(R.id.play_btn)
        speedBtn = root?.findViewById(R.id.speed_btn)
        strategyBtn = root?.findViewById(R.id.strategy_btn)

        bubbleView?.setOnTouchListener(bubbleTouch)
        root?.findViewById<View>(R.id.close_btn)?.setOnClickListener { setPanelVisible(false) }
        playBtn?.setOnClickListener { togglePlay() }
        speedBtn?.setOnClickListener { cycleSpeed() }
        strategyBtn?.setOnClickListener { cycleStrategy() }
        root?.findViewById<View>(R.id.calibrate_btn)?.setOnClickListener { openCalibration() }
        root?.findViewById<View>(R.id.quit_btn)?.setOnClickListener { stopSelf() }

        observe()
    }

    private val bubbleTouch = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                startRawX = event.rawX
                startRawY = event.rawY
                startWindowX = params?.x ?: 0
                startWindowY = params?.y ?: 0
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                if (Math.abs(dx) > dpTol() || Math.abs(dy) > dpTol()) dragging = true
                if (dragging && params != null) {
                    params!!.x = startWindowX + dx.toInt()
                    params!!.y = startWindowY + dy.toInt()
                    wm.updateViewLayout(root, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) setPanelVisible(panelView?.visibility != View.VISIBLE)
                dragging = false
                true
            }
            else -> false
        }
    }

    private fun setPanelVisible(visible: Boolean) {
        panelView?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) paintWidgets()
    }

    private fun togglePlay() {
        BotState.autoPaused.value = false
        BotState.playing.value = !BotState.playing.value
        if (BotState.playing.value) BotState.lastBoard.value = null
        paintWidgets()
    }

    private fun cycleSpeed() {
        val current = Prefs.speedMs
        val next = speeds[(speeds.indexOf(current).takeIf { it >= 0 } ?: 1).let { (it + 1) % speeds.size }]
        Prefs.speedMs = next
        paintWidgets()
    }

    private fun cycleStrategy() {
        val ids = Solvers.OPTIONS.map { it.first }
        val cur = Prefs.strategyId
        val idx = ids.indexOf(cur)
        val nextId = ids[(idx + 1) % ids.size]
        Prefs.strategyId = nextId
        BotState.configVersion.value += 1
        paintWidgets()
    }

    private fun openCalibration() {
        val i = Intent(this, com.tetra.bot.CalibrationActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
    }

    private fun paintWidgets() {
        playBtn?.text = if (BotState.playing.value) "⏸ Pause" else "▶ Resume"
        playBtn?.setTextColor(resources.getColor(if (BotState.playing.value) R.color.tetra_text else R.color.tetra_good, null))
        speedBtn?.text = "⚡ ${Prefs.speedMs / 1000.0}s"
        val id = Prefs.strategyId
        strategyBtn?.text = "🧠 ${Solvers.OPTIONS.firstOrNull { it.first == id }?.second?.removePrefix("Expectimax ")?.let { if (id.startsWith("expectimax")) "Exp. $it" else it } ?: id}"
    }

    private fun observe() {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                BotState.status, BotState.maxTileValue, BotState.moves, BotState.connected
            ) { status, mt, moves, connected ->
                val st = if (!connected) "⚠ Enable accessibility in Settings first" else status
                "$st\nMax tile: ${if (mt > 0) mt else "—"} · Moves: $moves"
            }.collectLatest { statusView?.text = it }
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Tetra control", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpTol(): Int = dp(12)

    override fun onDestroy() {
        BotState.overlayRunning.value = false
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tetra_control"
        private const val NOTIF_ID = 4271

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}