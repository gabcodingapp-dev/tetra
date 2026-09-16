package com.tetra.bot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tetra.bot.vision.Roi

/**
 * Overlay square drawn over the real game so the user can frame the
 * 4x4 board. Drag to move, or drag a corner/edge to resize.
 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val rect = RectF()
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

    private val density = resources.displayMetrics.density
    private val tol = 26f * density

    private var mode = MODE_NONE
    private var handle = 0
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        if (rect.isEmpty) {
            val side = minOf(w, h) * 0.72f
            val cx = w / 2f
            val cy = h / 2f
            rect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        }
        clamp()
        invalidate()
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
        canvas.drawText("Drag corners or edges to resize · drag inside to move", width / 2f, rect.top - 12f * density, subPaint)
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
                    MODE_MOVE -> {
                        rect.offset(dx, dy)
                    }
                    MODE_RESIZE -> {
                        when (handle) {
                            0 -> { rect.left = event.x; rect.top = event.y }
                            1 -> { rect.right = event.x; rect.top = event.y }
                            2 -> { rect.left = event.x; rect.bottom = event.y }
                            3 -> { rect.right = event.x; rect.bottom = event.y }
                            4 -> rect.top = event.y
                            5 -> rect.bottom = event.y
                            6 -> rect.left = event.x
                            7 -> rect.right = event.x
                        }
                    }
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

    private fun clamp() {
        val minSide = 120f * density
        if (rect.right - rect.left < minSide) {
            if (handle == 0 || handle == 2 || handle == 6) rect.left = rect.right - minSide
            else rect.right = rect.left + minSide
        }
        if (rect.bottom - rect.top < minSide) {
            if (handle == 0 || handle == 1 || handle == 4) rect.top = rect.bottom - minSide
            else rect.bottom = rect.top + minSide
        }
        rect.left = rect.left.coerceAtLeast(-40f * density)
        rect.top = rect.top.coerceAtLeast(-40f * density)
        rect.right = rect.right.coerceAtMost(width + 40f * density)
        rect.bottom = rect.bottom.coerceAtMost(height + 40f * density)
    }

    fun roi(): Roi = Roi.fromRect(rect, width, height)

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE = 2
    }
}