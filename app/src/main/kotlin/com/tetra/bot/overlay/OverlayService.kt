package com.tetra.bot.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.tetra.bot.CalibrationView
import com.tetra.bot.MainActivity
import com.tetra.bot.R
import com.tetra.bot.core.BotState
import com.tetra.bot.core.Prefs
import com.tetra.bot.engine.Board
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
    private var panelScroll: View? = null
    private var statusView: TextView? = null
    private var phaseChip: TextView? = null
    private var startBtn: View? = null
    private var pauseBtn: View? = null
    private var stopBtn: View? = null
    private var speedBtn: TextView? = null
    private var strategyBtn: TextView? = null

    private var alignRoot: FrameLayout? = null

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
        lp.x = dm.widthPixels - dp(110)
        lp.y = dm.heightPixels / 3
        params = lp
        wm.addView(root, lp)

        bubbleView = root?.findViewById(R.id.bubble)
        panelView = root?.findViewById(R.id.panel)
        panelScroll = root?.findViewById(R.id.panel_scroll)
        statusView = root?.findViewById(R.id.status_tv)
        phaseChip = root?.findViewById(R.id.phase_chip)
        startBtn = root?.findViewById(R.id.start_btn)
        pauseBtn = root?.findViewById(R.id.pause_btn)
        stopBtn = root?.findViewById(R.id.stop_btn)
        speedBtn = root?.findViewById(R.id.speed_btn)
        strategyBtn = root?.findViewById(R.id.strategy_btn)

        bubbleView?.setOnTouchListener(bubbleTouch)
        root?.findViewById<View>(R.id.close_btn)?.setOnClickListener { setPanelVisible(false) }
        root?.findViewById<View>(R.id.title_tv)?.setOnTouchListener(headerDrag)
        root?.findViewById<View>(R.id.resize_grip)?.setOnTouchListener(panelResizeTouch)
        startBtn?.setOnClickListener { pressStart() }
        pauseBtn?.setOnClickListener { pressPause() }
        stopBtn?.setOnClickListener { pressStop() }
        speedBtn?.setOnClickListener { cycleSpeed() }
        strategyBtn?.setOnClickListener { cycleStrategy() }
        root?.findViewById<View>(R.id.calibrate_btn)?.setOnClickListener { showAlignWindow() }
        root?.findViewById<View>(R.id.auto_align_btn)?.setOnClickListener { requestAutoDetect() }
        root?.findViewById<View>(R.id.quit_btn)?.setOnClickListener { stopSelf() }

        applyPanelWidth()
        paintWidgets()
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
        if (visible) {
            paintWidgets()
            shiftPanelOnScreen()
        }
    }

    /** Drag the panel by its header row. */
    private val headerDrag = View.OnTouchListener { _, event ->
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
                dragging = false
                true
            }
            else -> false
        }
    }

    /** Resize the panel: horizontal drag changes width, vertical drag changes height. */
    private var gripStartX = 0f
    private var gripStartY = 0f
    private var gripW = 0
    private var gripH = 0
    private val panelResizeTouch = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                gripStartX = event.rawX
                gripStartY = event.rawY
                gripW = panelView?.layoutParams?.width ?: dp(Prefs.panelWidth)
                gripH = panelScroll?.let { p ->
                    p.layoutParams?.height?.takeIf { it > 0 } ?: p.height
                } ?: 0
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val panel = panelView
                val scroll = panelScroll
                if (panel != null) {
                    val dm = resources.displayMetrics
                    val newW = (gripW + (event.rawX - gripStartX).toInt())
                        .coerceIn(dp(200), dm.widthPixels - dp(70))
                    panel.layoutParams = LinearLayout.LayoutParams(newW, LinearLayout.LayoutParams.WRAP_CONTENT)
                    Prefs.panelWidth = (newW / dm.density).toInt()

                    if (scroll != null && gripH > 0) {
                        val newH = (gripH + (event.rawY - gripStartY).toInt())
                            .coerceIn(dp(170), dm.heightPixels - dp(220))
                        scroll.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, newH
                        )
                        Prefs.panelHeight = (newH / dm.density).toInt()
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                shiftPanelOnScreen()
                true
            }
            else -> false
        }
    }

    private fun applyPanelWidth() {
        val panel = panelView ?: return
        panel.layoutParams = LinearLayout.LayoutParams(dp(Prefs.panelWidth), LinearLayout.LayoutParams.WRAP_CONTENT)
        val scroll = panelScroll ?: return
        if (Prefs.panelHeight > 0) {
            scroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(Prefs.panelHeight)
            )
        }
    }

    /** Keep the whole panel on screen after it expands alongside the bubble. */
    private fun shiftPanelOnScreen() {
        val lp = params ?: return
        val dm = resources.displayMetrics
        val totalW = dp(58) + dp(6) * 2 + dp(Prefs.panelWidth) + dp(4)
        val maxLeft = dm.widthPixels - totalW
        if (lp.x > maxLeft) {
            lp.x = maxOf(0, maxLeft)
            wm.updateViewLayout(root, lp)
        }
        val maxTop = dm.heightPixels - dp(580)
        if (lp.y > maxTop) {
            lp.y = maxOf(0, maxTop)
            wm.updateViewLayout(root, lp)
        }
    }

    /** Ask the bot to auto-locate the board from a live screenshot. */
    private fun requestAutoDetect() {
        BotState.autoDetectRequested.value = true
        BotState.status.value = "Detecting the 2048 board…"
    }

    /** Open the live, resizable alignment box over the game. */
    private fun showAlignWindow() {
        if (alignRoot != null) return
        // Don't swipe under an alignment view.
        if (BotState.phase.value == BotState.Phase.RUNNING) {
            BotState.phase.value = BotState.Phase.PAUSED
        }

        val root = LayoutInflater.from(this).inflate(R.layout.align_overlay, null) as FrameLayout
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        alignRoot = root

        root.findViewById<View>(R.id.align_auto)?.setOnClickListener {
            hideAlignWindow()
            requestAutoDetect()
        }
        root.findViewById<View>(R.id.align_save)?.setOnClickListener {
            val view = root.findViewById<CalibrationView>(R.id.align_view)
            Prefs.roi = view.roi()
            BotState.configVersion.value += 1
            BotState.lastBoard.value = null
            BotState.status.value = "Aligned ✓ — press ▶ Start"
            hideAlignWindow()
        }
        root.findViewById<View>(R.id.align_cancel)?.setOnClickListener { hideAlignWindow() }
    }

    private fun hideAlignWindow() {
        alignRoot?.let { runCatching { wm.removeView(it) } }
        alignRoot = null
    }

    /** Bot required to advance a fresh round. */
    private fun pressStart() {
        BotState.autoPaused.value = false
        BotState.phase.value = BotState.Phase.RUNNING
        BotState.lastBoard.value = null
        BotState.moves.value = 0
        BotState.maxTileValue.value = 0
        BotState.status.value = "▶ Started — reading the board…"
        paintWidgets()
    }

    /** Freeze the bot in place; nothing moves until Start again. */
    private fun pressPause() {
        BotState.autoPaused.value = false
        BotState.phase.value = BotState.Phase.PAUSED
        BotState.status.value = "Paused"
        paintWidgets()
    }

    /** Full stop + reset; the bot ignores the board until Start. */
    private fun pressStop() {
        BotState.autoPaused.value = false
        BotState.phase.value = BotState.Phase.STOPPED
        BotState.lastBoard.value = null
        BotState.moves.value = 0
        BotState.maxTileValue.value = 0
        BotState.status.value = "Stopped"
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

    private fun paintWidgets() {
        paintPhaseUi(BotState.phase.value)

        speedBtn?.text = "⚡ ${Prefs.speedMs / 1000.0}s"
        val id = Prefs.strategyId
        strategyBtn?.text =
            Solvers.OPTIONS.firstOrNull { it.first == id }?.second
                ?.let { if (id.startsWith("expectimax")) "Exp. ${it.removePrefix("Expectimax ")}" else it }
                ?: id
    }

    private fun paintPhaseUi(phase: BotState.Phase) {
        startBtn?.isEnabled = phase != BotState.Phase.RUNNING
        pauseBtn?.isEnabled = phase == BotState.Phase.RUNNING
        stopBtn?.isEnabled = phase != BotState.Phase.STOPPED

        val (label, color) = when (phase) {
            BotState.Phase.RUNNING -> "● Running" to R.color.tetra_good
            BotState.Phase.PAUSED -> "● Paused" to R.color.tetra_warn
            BotState.Phase.STOPPED -> "● Stopped" to R.color.tetra_text_dim
        }
        phaseChip?.text = label
        phaseChip?.setTextColor(resources.getColor(color, null))
    }

    private fun observe() {
        scope.launch {
            combine(
                BotState.status, BotState.maxTileValue, BotState.moves,
                BotState.connected, BotState.phase
            ) { status, mt, moves, connected, phase ->
                paintPhaseUi(phase)
                val st = if (!connected) "⚠ Enable accessibility in Settings first" else status
                Triple(st, mt, moves)
            }.combine(BotState.lastBoard) { base, board ->
                val (st, mt, moves) = base
                val preview = boardPreview(board)
                if (preview.isNotEmpty()) {
                    "$st\nMax tile: ${if (mt > 0) mt else "—"} · Moves: $moves\n$preview"
                } else {
                    "$st\nMax tile: ${if (mt > 0) mt else "—"} · Moves: $moves"
                }
            }.collectLatest { statusView?.text = it }
        }
    }

    /** Compact 4x4 readback of the board the bot currently sees. */
    private fun boardPreview(b: ULong?): String {
        if (b == null) return ""
        val sb = StringBuilder()
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                val e = Board.cellExponent(b, r, c)
                val v = if (e == 0) "·" else (1 shl e).toString()
                sb.append(v.padStart(4))
            }
            if (r < 3) sb.append('\n')
        }
        return sb.toString()
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
        hideAlignWindow()
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