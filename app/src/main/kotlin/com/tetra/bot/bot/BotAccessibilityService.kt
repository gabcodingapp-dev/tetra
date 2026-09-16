package com.tetra.bot.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.tetra.bot.core.BotState
import com.tetra.bot.core.Prefs
import com.tetra.bot.engine.Board
import com.tetra.bot.engine.Solver
import com.tetra.bot.engine.Solvers
import com.tetra.bot.vision.BoardReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class BotAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor by lazy { Executor { r -> Thread(r, "capture").start() } }

    private var reader: BoardReader? = null
    private var solver: Solver? = null
    private var configSeen = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        BotState.connected.value = true
        BotState.phase.value = BotState.Phase.STOPPED
        BotState.autoPaused.value = false
        BotState.status.value = "Ready — tap the bubble and press ▶ Start"
        BotState.resetStats()
        scope.launch { runLoop() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        BotState.connected.value = false
        BotState.phase.value = BotState.Phase.STOPPED
        BotState.resetStats()
        scope.cancel()
    }

    private suspend fun runLoop() {
        var unchangedStreak = 0
        while (true) {
            when (BotState.phase.value) {
                BotState.Phase.STOPPED -> {
                    BotState.status.value = "Stopped — get your 2048 board on screen, then press ▶ Start"
                    delay(400)
                    continue
                }
                BotState.Phase.PAUSED -> {
                    BotState.status.value =
                        if (BotState.autoPaused.value) "Game over — start a new game, then press ▶ Start"
                        else "Paused — press ▶ Start to resume"
                    delay(250)
                    continue
                }
                BotState.Phase.RUNNING -> Unit
            }

            if (Build.VERSION.SDK_INT < 30) {
                BotState.status.value = "Screenshots need Android 11+ (API 30) — this device can't run the bot"
                delay(2000)
                continue
            }

            val roi = Prefs.roi
            if (roi == null) {
                BotState.status.value = "Not calibrated — tap the bubble → ⌖ Align"
                delay(400)
                continue
            }

            if (configSeen != BotState.configVersion.value) {
                configSeen = BotState.configVersion.value
                reader = BoardReader(roi)
                solver = Solvers.create(Prefs.strategyId)
            }

            val shot = capture()
            if (shot == null) {
                BotState.status.value = "Snapshot failed — grant access and open the 2048 app"
                delay(500)
                continue
            }

            var board: ULong? = null
            try {
                board = reader?.read(shot.bmp)
            } finally {
                shot.release()
            }
            if (board == null) {
                BotState.status.value = "Can't see a board — open the 2048 app"
                BotState.lastBoard.value = null
                delay(400)
                continue
            }

            if (Board.isGameOver(board)) {
                BotState.status.value = "Game over — start a new game, then press ▶ Start"
                BotState.autoPaused.value = true
                BotState.phase.value = BotState.Phase.PAUSED
                unchangedStreak = 0
                delay(300)
                continue
            }

            val prev = BotState.lastBoard.value
            if (board == prev) {
                unchangedStreak++
                if (unchangedStreak >= 5) {
                    BotState.status.value = "Board not changing — paused"
                    BotState.autoPaused.value = true
                    BotState.phase.value = BotState.Phase.PAUSED
                    unchangedStreak = 0
                    continue
                }
                delay(220)
                continue
            }
            unchangedStreak = 0
            BotState.lastBoard.value = board
            val mt = Board.maxTile(board)
            if (mt > 0) BotState.maxTileValue.value = 1 shl mt
            BotState.moves.value += 1

            val dir = solver?.pickMove(board) ?: 0
            val nb = Board.makeMove(board, dir)
            if (nb == board) {
                BotState.status.value = "Stuck — check the alignment"
                continue
            }
            BotState.status.value = "Swipe ${DIR_NAMES[dir]}  (${1 shl mt} tile)"
            dispatchSwipe(dir)

            // give the app time to animate + spawn the new tile
            delay(Prefs.speedMs)
        }
    }

    private suspend fun capture(): Shot? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            takeScreenshotCompat(
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hw = screenshot.hardwareBuffer
                        val bmp = hw?.let { runCatching { Bitmap.wrapHardwareBuffer(it, screenshot.colorSpace) }.getOrNull() }
                        if (bmp == null) {
                            hw?.close()
                            cont.resume(null)
                        } else {
                            cont.resume(Shot(bmp, hw))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(null)
                    }
                }
            )
        }
    }

    /**
     * The 2-arg takeScreenshot(Executor, Callback) existed between API 30 and 34 but was removed
     * in API 35, where only takeScreenshot(int displayId, Executor, Callback) remains.
     */
    @SuppressLint("DiscouragedApi")
    private fun takeScreenshotCompat(callback: TakeScreenshotCallback) {
        if (Build.VERSION.SDK_INT >= 35) {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            return
        }
        var handled = false
        try {
            val method = AccessibilityService::class.java.getMethod(
                "takeScreenshot", Executor::class.java, TakeScreenshotCallback::class.java
            )
            method.invoke(this, executor, callback)
            handled = true
        } catch (_: Throwable) {
        }
        if (!handled) callback.onFailure(1)
    }

    private fun dispatchSwipe(dir: Int) {
        val rect = reader?.lastRect ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        val cell = rect.width() / 4f
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()

        val inset = cell * 0.45f
        val s = when (dir) {
            0 -> Swipe(rect.right - inset, cy, rect.left + inset, cy)   // left
            1 -> Swipe(cx, rect.bottom - inset, cx, rect.top + inset)   // up
            2 -> Swipe(rect.left + inset, cy, rect.right - inset, cy)   // right
            else -> Swipe(cx, rect.top + inset, cx, rect.bottom - inset) // down
        }

        val path = Path().apply { moveTo(s.x1, s.y1); lineTo(s.x2, s.y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /** Start/end points of a swipe expressed in screen pixels. */
    private data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private class Shot(val bmp: Bitmap, val buffer: android.hardware.HardwareBuffer) {
        fun release() {
            bmp.recycle()
            buffer.close()
        }
    }

    companion object {
        private val DIR_NAMES = arrayOf("left", "up", "right", "down")
    }
}