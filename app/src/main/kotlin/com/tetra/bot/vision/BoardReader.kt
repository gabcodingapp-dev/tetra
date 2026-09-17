package com.tetra.bot.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.tetra.bot.engine.Board

/** Reads a 2048 board from a screenshot region of interest. */
class BoardReader(private val roi: Roi) {

    /** last computed screen rect (for swipes) */
    var lastRect: Rect? = null
        private set

    /** Returns the bit-packed board, or null if the region is unusable. */
    fun read(bmp: Bitmap): ULong? {
        if (bmp.width <= 0 || bmp.height <= 0 || !roi.isValid()) return null
        // A HARDWARE (GPU) bitmap can't be sampled — never one of those here.
        if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) return null
        val rect = roi.on(bmp.width, bmp.height)
        lastRect = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        val cell = rect.width() / 4f
        if (cell < 24f) return null

        var board = 0uL
        val s = (cell * 0.26f).toInt().coerceAtLeast(2)
        val cx = cell * 0.5f
        val cy = cell * 0.5f

        var nonEmpty = 0
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                val px = (rect.left + c * cell + cx).toInt()
                val py = (rect.top + r * cell + cy).toInt()
                val avg = averageSample(bmp, px, py, s)
                val exp = Palette.classify(avg[0], avg[1], avg[2])
                if (exp in 1..15) {
                    board = board or (exp.toULong() shl Board.nibbleOffset(r, c))
                    nonEmpty++
                }
            }
        }
        if (nonEmpty < 2) return null // a real 2048 board always shows at least 2 tiles
        return board
    }

    /** Average a 3x3 window of samples around the cell center (off-center weights). */
    private fun averageSample(bmp: Bitmap, cx: Int, cy: Int, s: Int): IntArray {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        val offsets = intArrayOf(-s, -s, -s, 0, -s, s, 0, -s, 0, 0, 0, s, s, -s, s, 0, s, s)
        for (i in 0 until offsets.size step 2) {
            val x = (cx + offsets[i]).coerceIn(0, bmp.width - 1)
            val y = (cy + offsets[i + 1]).coerceIn(0, bmp.height - 1)
            val p = bmp.getPixel(x, y)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
        }
        return intArrayOf(r / n, g / n, b / n)
    }
}