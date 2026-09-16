package com.tetra.bot.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
        BotState.playing.value = true
        BotState.resetStats()
        scope.launch { runLoop() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        BotState.connected.value = false
        BotState.resetStats()
    }

    private suspend fun runLoop() {
        var unchangedStreak = 0
        while (true) {
            if (!isActive) break

            if (!BotState.playing.value) {
                BotState.status.value =
                    if (BotState.autoPaused.value) "Game over — start a new game, then press play"
                    else "Paused"
                delay(250)
                continue
            }

            val roi = Prefs.roi
            if (roi == null) {
                BotState.status.value = "Not calibrated — tap the bubble → Calibrate"
                delay(400)
                continue
            }

            if (configSeen != BotState.configVersion.value) {
                configSeen = BotState.configVersion.value
                reader = BoardReader(roi)
                solver = Solvers.create(Prefs.strategyId)
            }

            val shot = capture() ?: run { delay(300); continue }

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
                BotState.status.value = "Game over — start a new game, then press play"
                BotState.autoPaused.value = true
                BotState.playing.value = false
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
                    BotState.playing.value = false
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
                BotState.status.value = "Stuck — check calibration"
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
            takeScreenshot(
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bmp = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        if (bmp == null) {
                            screenshot.hardwareBuffer.close()
                            cont.resume(null)
                        } else {
                            cont.resume(Shot(bmp, screenshot.hardwareBuffer))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(null)
                    }
                }
            )
        }
    }

    private fun dispatchSwipe(dir: Int) {
        val rect = reader?.lastRect ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        val cell = rect.width() / 4f
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()

        val (x1, y1, x2, y2) = when (dir) {
            0 -> Triple(rect.right - (cell * 0.45f).toInt(), cy, rect.left + (cell * 0.45f).toInt(), cy)  // left
            1 -> Triple(cx, rect.bottom - (cell * 0.45f).toInt(), cx, rect.top + (cell * 0.45f).toInt())  // up
            2 -> Triple(rect.left + (cell * 0.45f).toInt(), cy, rect.right - (cell * 0.45f).toInt(), cy)  // right
            else -> Triple(cx, rect.top + (cell * 0.45f).toInt(), cx, rect.bottom - (cell * 0.45f).toInt()) // down
        }

        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

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