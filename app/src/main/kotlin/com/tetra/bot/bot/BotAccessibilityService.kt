package com.tetra.bot.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.tetra.bot.core.BotState
import com.tetra.bot.core.Prefs
import com.tetra.bot.engine.Board
import com.tetra.bot.engine.Solver
import com.tetra.bot.engine.Solvers
import com.tetra.bot.vision.BoardDetector
import com.tetra.bot.vision.BoardReader
import com.tetra.bot.vision.Roi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class BotAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t ->
                BotState.status.value = "⚠ Bot error: ${t.message ?: t.javaClass.simpleName} — tap ▶ Start"
            }
    )
    private val executor by lazy { Executor { r -> Thread(r, "capture").start() } }

    private var reader: BoardReader? = null
    private var solver: Solver? = null
    private var configSeen = -1
    private var lastAutoAlign = 0L

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
        var stuckStreak = 0
        while (true) {
            // A single bad iteration must never take down the process — catch and
            // report instead, so the bot keeps working on the next pass.
            try {
                if (BotState.debugShotRequested.value) {
                    takeDebugShot()
                    continue
                }

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

                if (BotState.autoDetectRequested.value) {
                    performAutoDetect()
                    continue
                }

                if (Build.VERSION.SDK_INT < 30) {
                    BotState.status.value = "Screenshots need Android 11+ (API 30) — this device can't run the bot"
                    delay(2000)
                    continue
                }

                val roi = Prefs.roi
                if (roi == null) {
                    // First run: find the board automatically instead of asking.
                    val ok = autoAlignFromScreenshot("Looking for the board…")
                    if (!ok) BotState.status.value = "Not detected — tap bubble → ⌖ Align (drag the box onto the grid) and Save"
                    delay(1200)
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

                // Never read or swipe while our own floating UI covers the grid —
                // the panel's preview text would be mistaken for the board.
                val ov = BotState.overlayRect.value
                val roiScreen = roi.on(shot.bmp.width, shot.bmp.height).let {
                    Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                }
                if (ov != null && Rect.intersects(ov, roiScreen)) {
                    BotState.status.value = "⚠ Move the Tetra bubble/panel off the board — it blocks the bot's view"
                    delay(600)
                    continue
                }

                var board: ULong? = null
                try {
                    board = reader?.read(shot.bmp)
                    if (board == null) {
                        // Self-heal: the saved box may not cover the grid, so try to
                        // re-locate the board right here and retry on the next pass.
                        val found = BoardDetector.detect(shot.bmp)
                            ?: BoardDetector.detect(shot.bmp, relaxed = true)
                        val newRoi = found?.let { Roi.fromRect(it, shot.bmp.width, shot.bmp.height) }
                        if (newRoi != null && newRoi != roi) {
                            Prefs.roi = newRoi
                            BotState.configVersion.value += 1
                            BotState.lastBoard.value = null
                            BotState.status.value = "Board re-aligned automatically — retrying…"
} else {
                        BotState.status.value =
                            "Can't see a board — tap bubble → ⌖ Align, drag the box onto the grid, Save"
                        BotState.lastBoard.value = null
                    }
                    saveDebug(shot.bmp, "noboard")
                }
                } finally {
                    shot.release()
                }
                if (board == null) {
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
                    // Swipes aren't reaching the grid — try to re-locate it before
                    // (later) pausing, in case the box drifted off the board.
                    if (unchangedStreak >= 3 && unchangedStreak < 5) {
                        autoAlignFromScreenshot("Board not changing — re-aligning…", saveTag = "stall")
                    }
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

                val dir = runCatching { solver?.pickMove(board) }.getOrNull() ?: 0
                val nb = Board.makeMove(board, dir)
                if (nb == board) {
                    stuckStreak++
                    if (stuckStreak >= 3) {
                        BotState.status.value = "Stuck — the board isn't moving. Re-check alignment."
                        BotState.autoPaused.value = true
                        BotState.phase.value = BotState.Phase.PAUSED
                        stuckStreak = 0
                    } else {
                        delay(300)
                    }
                    continue
                }
                stuckStreak = 0
                BotState.status.value = "Swipe ${DIR_NAMES[dir]}  (${1 shl mt} tile)"
                dispatchSwipe(dir)

                // give the app time to animate + spawn the new tile
                delay(Prefs.speedMs)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                BotState.status.value = "⚠ Recovered: $msg — still running"
                delay(300)
            }
        }
    }

    /** Auto-locate the board from a fresh screenshot and save it as the ROI. */
    private suspend fun performAutoDetect() {
        BotState.autoDetectRequested.value = false
        if (Build.VERSION.SDK_INT < 30) {
            BotState.status.value = "Auto-align needs Android 11+ (API 30)"
            return
        }
        autoAlignFromScreenshot("Looking for the 2048 board…")
    }

    /** True when a board was found and saved as the new ROI (or was already set). */
    private suspend fun autoAlignFromScreenshot(status: String, saveTag: String? = null): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAutoAlign < 2000) return false
        lastAutoAlign = now
        BotState.status.value = status
        val shot = capture()
        if (shot == null) {
            BotState.status.value = "Snapshot failed — open the 2048 app and retry"
            return false
        }
        try {
            if (saveTag != null) saveDebug(shot.bmp, saveTag)
            val rect = BoardDetector.detect(shot.bmp)
            if (rect == null) return false
            Prefs.roi = Roi.fromRect(rect, shot.bmp.width, shot.bmp.height)
            BotState.configVersion.value += 1
            BotState.lastBoard.value = null
            BotState.status.value = "Board aligned ✓ — press ▶ Start"
            return true
        } finally {
            shot.release()
        }
    }

    /** Grab a fresh screenshot on request (used by the panel's 📤 Send shot). */
    private suspend fun takeDebugShot() {
        BotState.debugShotRequested.value = false
        if (Build.VERSION.SDK_INT < 30) {
            BotState.status.value = "Screenshots need Android 11+ (API 30)"
            return
        }
        val shot = capture()
        if (shot == null) {
            BotState.status.value = "Snapshot failed — open the 2048 app and retry"
            return
        }
        val saved = try { saveDebug(shot.bmp, "manual") } finally { shot.release() }
        BotState.status.value =
            if (saved != null) "Screenshot saved ✓ — tap 📤 Send shot"
            else "Couldn't save the screenshot"
    }

    /**
     * Store the last captured frame as a PNG under Pictures/tetra-debug so the
     * user can send us exactly what the bot is (or isn't) seeing.
     */
    private fun saveDebug(bmp: Bitmap, tag: String): File? = try {
        val dir = File(getExternalFilesDir(null), "Pictures/tetra-debug")
        dir.mkdirs()
        val f = File(dir, "tetra_${tag}_${System.currentTimeMillis()}_${bmp.width}x${bmp.height}.png")
        FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.size > 14) {
            files.sortedBy { it.lastModified() }.take(files.size - 14).forEach { it.delete() }
        }
        BotState.lastDebugShot.value = f
        f
    } catch (_: Throwable) {
        null
    }

    private suspend fun capture(): Shot? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            val holder = arrayOfNulls<Shot>(1)
            cont.invokeOnCancellation { holder[0]?.release() }
            val cb = object : TakeScreenshotCallback {
                private var done = false
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (done) return
                    done = true
                    val hw = screenshot.hardwareBuffer
                    // takeScreenshot yields a GPU-backed HARDWARE bitmap whose pixels
                    // CANNOT be read ("pixel access is not supported on this config").
                    // Force a software ARGB_8888 copy, with a canvas raster fallback.
                    val wrapped = hw?.let {
                        runCatching { Bitmap.wrapHardwareBuffer(it, screenshot.colorSpace) }.getOrNull()
                    }
                    val bmp = wrapped?.let { softwareCopy(it) }
                    if (bmp == null) {
                        hw?.close()
                        runCatching { cont.resume(null) }
                    } else {
                        holder[0] = Shot(bmp, hw)
                        runCatching { cont.resume(holder[0]) }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (done) return
                    done = true
                    runCatching { cont.resume(null) }
                }
            }
            takeScreenshotCompat(cb)
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

        // Start inside the first tile, finish inside the last tile — a long,
        // fast flick the 2048 app reliably recognises as a swipe.
        val inset = cell * 0.30f
        val s = when (dir) {
            0 -> Swipe(rect.right - inset, cy, rect.left + inset, cy)   // left
            1 -> Swipe(cx, rect.bottom - inset, cx, rect.top + inset)   // up
            2 -> Swipe(rect.left + inset, cy, rect.right - inset, cy)   // right
            else -> Swipe(cx, rect.top + inset, cx, rect.bottom - inset) // down
        }

        val path = Path().apply { moveTo(s.x1, s.y1); lineTo(s.x2, s.y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 180)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /** Start/end points of a swipe expressed in screen pixels. */
    private data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    /**
     * Guarantee a software, pixel-readable bitmap regardless of the source
     * config — never returns a HARDWARE (GPU) bitmap.
     */
    private fun softwareCopy(src: Bitmap): Bitmap? {
        val cfg = src.config
        // Ordinary software configs are already pixel-readable — pass through.
        if (cfg != null && cfg != Bitmap.Config.HARDWARE && cfg != Bitmap.Config.RGBA_F16) {
            return src
        }
        var copy = runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (copy == null || copy.config == Bitmap.Config.HARDWARE) {
            // Last resort: rasterize onto a fresh software canvas.
            copy = runCatching {
                val c = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                Canvas(c).drawBitmap(src, 0f, 0f, null)
                c
            }.getOrNull()
        }
        return copy
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