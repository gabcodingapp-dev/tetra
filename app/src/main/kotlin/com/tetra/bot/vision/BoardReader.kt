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

        val inset = (cell * 0.28f).toInt().coerceAtLeast(2)
        val ox = cell * 0.5f
        val oy = cell * 0.5f

        // Pass 1: sample every cell center.
        val samples = Array(16) { IntArray(3) }
        for (i in 0 until 16) {
            val r = i / 4
            val c = i % 4
            val px = (rect.left + c * cell + ox).toInt()
            val py = (rect.top + r * cell + oy).toInt()
            samples[i] = cornerSample(bmp, px, py, inset)
        }

        // The empty-tile reference is the sampled cell nearest the classic empty
        // beige. Empty tiles dominate early game, so this is stable across themes.
        val ref = samples.minByOrNull { sqDist(it, EMPTY_RGB) } ?: return null
        val refExp = Palette.classify(ref[0], ref[1], ref[2])

        var board = 0uL
        var empty = 0
        for (i in 0 until 16) {
            val col = samples[i]
            val exp = Palette.classify(col[0], col[1], col[2])
            // A cell holds a tile when its palette identity differs from the empty
            // reference (palette match is what separates the very close classic
            // empty #CDC1B4 / tile-2 #EEE4DA pair), or when it clearly deviates
            // in colour from the reference (unknown themes whose shades compress).
            val isTile = exp != refExp || maxChannelDist(col, ref) > EMPTY_DELTA
            if (isTile) {
                val v = if (exp == 0) 1 else exp // untypable tile: treat as a 2
                val r = i / 4
                val c = i % 4
                board = board or (v.toULong() shl Board.nibbleOffset(r, c))
            } else {
                empty++
            }
        }
        // A board needs at least one tile visible; one that reads as FULL of tiles
        // is almost certainly not over the grid.
        if (empty < 1 || empty >= 16) return null
        return board
    }

    /**
     * Average the four corner probes around the cell center. The dark number
     * glyphs occupy only the middle of a tile, so the corners are always pure
     * tile background (or pure empty background) — a center-averaged window
     * pulls glyph pixels toward the empty reference and loses tiles.
     */
    private fun cornerSample(bmp: Bitmap, cx: Int, cy: Int, s: Int): IntArray {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (dx in intArrayOf(-s, -s, s, s)) {
            for (dy in intArrayOf(-s, s, -s, s)) {
                val x = (cx + dx).coerceIn(0, bmp.width - 1)
                val y = (cy + dy).coerceIn(0, bmp.height - 1)
                val p = bmp.getPixel(x, y)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
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

        // A cell is "still the empty background" when its biggest channel
        // deviation from the empty reference stays under this. Classic empty and
        // tile-2 are only ~25-45 apart on many (especially low-res) screens, so
        // this must stay tight — palette identity does the real separating.
        private const val EMPTY_DELTA = 20
    }
}