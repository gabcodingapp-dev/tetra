package com.tetra.bot.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.tetra.bot.engine.Board
import kotlin.math.abs

/** Reads a 2048 board from a screenshot region of interest. */
class BoardReader(private val roi: Roi) {

    /** last computed screen rect (for swipes) */
    var lastRect: Rect? = null
        private set

    /**
     * Returns the bit-packed board, or null if the region is unusable.
     *
     * Reading is adaptive: instead of requiring the exact reference palette, it
     * derives the "empty tile" color from the sampled cells themselves (the
     * most common early-game color) and flags any cell that clearly differs.
     * This keeps working across color themes, dark modes and minor palette
     * shifts, while values still use the classic palette as a best effort.
     */
    fun read(bmp: Bitmap): ULong? {
        if (bmp.width <= 0 || bmp.height <= 0 || !roi.isValid()) return null
        // A HARDWARE (GPU) bitmap can't be sampled — never one of those here.
        if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) return null
        val rect = roi.on(bmp.width, bmp.height)
        lastRect = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        val cell = rect.width() / 4f
        if (cell < 14f) return null

        val s = (cell * 0.26f).toInt().coerceAtLeast(2)
        val ox = cell * 0.5f
        val oy = cell * 0.5f

        // Pass 1: sample every cell center.
        val samples = Array(16) { IntArray(3) }
        for (i in 0 until 16) {
            val r = i / 4
            val c = i % 4
            val px = (rect.left + c * cell + ox).toInt()
            val py = (rect.top + r * cell + oy).toInt()
            samples[i] = averageSample(bmp, px, py, s)
        }

        // The empty-tile reference is the sampled cell nearest the classic empty
        // beige. Empty tiles dominate early game, so this is stable across themes.
        val ref = samples.minByOrNull { sqDist(it, EMPTY_RGB) } ?: return null

        var board = 0uL
        var empty = 0
        for (i in 0 until 16) {
            val col = samples[i]
            if (maxChannelDist(col, ref) > 88) {
                var exp = Palette.classify(col[0], col[1], col[2])
                if (exp == 0) exp = 1 // unknown theme: treat an untypable tile as a 2
                val r = i / 4
                val c = i % 4
                board = board or (exp.toULong() shl Board.nibbleOffset(r, c))
            } else {
                empty++
            }
        }
        // A board needs at least one tile visible; one that reads as FULL of tiles
        // is almost certainly not over the grid.
        if (empty < 1 || empty >= 16) return null
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

    private fun sqDist(a: IntArray, b: IntArray): Int {
        val dr = a[0] - b[0]
        val dg = a[1] - b[1]
        val db = a[2] - b[2]
        return dr * dr + dg * dg + db * db
    }

    private fun maxChannelDist(a: IntArray, b: IntArray): Int =
        maxOf(abs(a[0] - b[0]), abs(a[1] - b[1]), abs(a[2] - b[2]))

    companion object {
        private val EMPTY_RGB = intArrayOf(0xCD, 0xC1, 0xB4)
    }
}