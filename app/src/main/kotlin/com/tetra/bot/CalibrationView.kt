package com.tetra.bot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tetra.bot.core.Prefs
import com.tetra.bot.vision.Roi

/**
 * Overlay square drawn over the real game so the user can frame the
 * 4x4 board. Drag to move, or drag a corner/edge to resize — the rect
 * always stays square so rows and columns map cleanly to the grid.
 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val gridPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val outlinePaint = Paint().apply {
        color = Color.rgb(237, 194, 46)
        strokeWidth = 4f * density
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.rgb(237, 194, 46)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 16f * density
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        textSize = 13f * density
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val tol = 26f * density

    private var mode = MODE_NONE
    private var handle = 0
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var restored = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        if (!restored) {
            restored = true
            restoreAndPosition()
        }
        clamp()
        invalidate()
    }

    /** First layout: use the last saved alignment, otherwise center a default box. */
    private fun restoreAndPosition() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val prev = Prefs.roi
        if (prev != null && prev.isValid()) {
            rect.set(
                prev.left * sw,
                prev.top * sh,
                prev.right * sw,
                prev.bottom * sh
            )
        } else {
            val side = minOf(sw, sh) * 0.72f
            val cx = sw / 2f
            val cy = sh / 2f
            rect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // 4x4 grid
        val cell = rect.width() / 4f
        for (i in 0..4) {
            val x = rect.left + i * cell
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
        }
        for (i in 0..4) {
            val y = rect.top + i * cell
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }

        canvas.drawRect(rect, outlinePaint)

        val r = 12f * density
        for ((hx, hy) in handlePoints()) {
            canvas.drawCircle(hx, hy, r, handlePaint)
        }

        canvas.drawText(
            "Align the square over the 4×4 board",
            width / 2f, rect.top - 34f * density, textPaint
        )
        canvas.drawText(
            "Drag corners or edges to resize · drag inside to move",
            width / 2f, rect.top - 12f * density, subPaint
        )
    }

    private fun handlePoints(): List<Pair<Float, Float>> = listOf(
        rect.left to rect.top,
        rect.right to rect.top,
        rect.left to rect.bottom,
        rect.right to rect.bottom,
        rect.centerX() to rect.top,
        rect.centerX() to rect.bottom,
        rect.left to rect.centerY(),
        rect.right to rect.centerY()
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val pts = handlePoints()
                var hit = -1
                for (i in pts.indices) {
                    val (x, y) = pts[i]
                    if (Math.hypot((event.x - x).toDouble(), (event.y - y).toDouble()) < tol) {
                        hit = i
                        break
                    }
                }
                if (hit >= 0) {
                    mode = MODE_RESIZE
                    handle = hit
                    setupResizeAnchor()
                } else if (rect.contains(event.x, event.y)) {
                    mode = MODE_MOVE
                } else {
                    mode = MODE_NONE
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                when (mode) {
                    MODE_MOVE -> rect.offset(dx, dy)
                    MODE_RESIZE -> resizeFrom(anchorX, anchorY, event.x, event.y)
                }
                clamp()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                mode = MODE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Freeze the opposite corner/edge so the square grows around it. */
    private fun setupResizeAnchor() {
        when (handle) {
            0 -> { anchorX = rect.right; anchorY = rect.bottom } // drag top-left
            1 -> { anchorX = rect.left; anchorY = rect.bottom }  // drag top-right
            2 -> { anchorX = rect.right; anchorY = rect.top }    // drag bottom-left
            3 -> { anchorX = rect.left; anchorY = rect.top }     // drag bottom-right
            else -> { anchorX = -1f; anchorY = -1f }             // edge handles: handled inline
        }
    }

    /** Rebuild a square rect from a fixed anchor + the live finger position. */
    private fun resizeFrom(ax: Float, ay: Float, px: Float, py: Float) {
        when (handle) {
            0, 1, 2, 3 -> {
                val side = maxOf(Math.abs(px - ax), Math.abs(py - ay))
                when (handle) {
                    0 -> rect.set(ax - side, ay - side, ax, ay)
                    1 -> rect.set(ax, ay - side, ax + side, ay)
                    2 -> rect.set(ax - side, ay, ax, ay + side)
                    3 -> rect.set(ax, ay, ax + side, ay + side)
                }
            }
            4 -> { // top edge, bottom edge fixed
                val side = Math.abs(py - rect.bottom)
                val cx = px
                rect.set(cx - side / 2, rect.bottom - side, cx + side / 2, rect.bottom)
            }
            5 -> { // bottom edge, top edge fixed
                val side = Math.abs(py - rect.top)
                val cx = px
                rect.set(cx - side / 2, rect.top, cx + side / 2, rect.top + side)
            }
            6 -> { // left edge, right edge fixed
                val side = Math.abs(px - rect.right)
                val cy = py
                rect.set(rect.right - side, cy - side / 2, rect.right, cy + side / 2)
            }
            7 -> { // right edge, left edge fixed
                val side = Math.abs(px - rect.left)
                val cy = py
                rect.set(rect.left, cy - side / 2, rect.left + side, cy + side / 2)
            }
        }
    }

    private fun clamp() {
        val minSide = 120f * density
        val side = maxOf(rect.width(), minSide).coerceAtMost(minOf(width.toFloat(), height.toFloat()))
        val cx = rect.centerX().coerceIn(side / 2, width - side / 2)
        val cy = rect.centerY().coerceIn(side / 2, height - side / 2)
        rect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }

    /**
     * The normalized on-screen region. Uses the REAL screen size (not the view
     * size) so the result matches the full-resolution screenshot the bot reads.
     */
    fun roi(): Roi {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val rectInScreen = RectF(
            rect.left * (screenW.toFloat() / width),
            rect.top * (screenH.toFloat() / height),
            rect.right * (screenW.toFloat() / width),
            rect.bottom * (screenH.toFloat() / height)
        )
        return Roi.fromRect(rectInScreen, screenW, screenH)
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE = 2
    }
}