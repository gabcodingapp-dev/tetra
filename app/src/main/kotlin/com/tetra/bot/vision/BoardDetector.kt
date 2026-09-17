package com.tetra.bot.vision

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Best-effort automatic locator for the 4x4 2048 board inside a screenshot.
 *
 * It works by first finding the beige "board" region (the classic
 * gabrielecirulli frame #BBADA0 / empty-tile #CDC1B4 colors), then locating the
 * three interior grid-gap lines in each direction to snap precisely onto the
 * 4 columns and 4 rows. Returns a pixel-space rect of the board, or null when
 * no board is found confidently.
 */
object BoardDetector {

    private const val TOL = 30

    fun detect(bmp: Bitmap, relaxed: Boolean = false): RectF? {
        val w = bmp.width
        val h = bmp.height
        if (w < 240 || h < 240) return null
        val tol = if (relaxed) TOL * 2 else TOL
        val minSideFrac = if (relaxed) 0.22f else 0.28f
        val maxSideFrac = if (relaxed) 1.0f else 0.95f

        // ---- 1) bounding box of beige (frame + empty tile) pixels ----
        val step = (minOf(w, h) / 220).coerceIn(2, 8)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var count = 0
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (isBeige(bmp.getPixel(x, y), tol)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    count++
                }
            }
        }

        if (count < 40 || minX > maxX || minY > maxY) return null
        val bboxW = maxX - minX
        val bboxH = maxY - minY
        val minScreen = minOf(w, h).toFloat()
        if (bboxW < minScreen * minSideFrac || bboxH < minScreen * minSideFrac) return null
        // A board shouldn't cover almost the entire screen — too much beige is likely noise.
        if (maxOf(bboxW, bboxH) > minScreen * maxSideFrac) return null

        // ---- 2) locate the 3 interior vertical gap lines ----
        val cy = (minY + maxY) / 2
        val stripRows = intArrayOf(cy - 12, cy - 8, cy - 4, cy, cy + 4, cy + 8, cy + 12)
        val vGaps = detectGapColumns(bmp, minX, maxX, stripRows)

        // ---- 3) locate the 3 interior horizontal gap lines ----
        val cx = (minX + maxX) / 2
        val stripCols = intArrayOf(cx - 12, cx - 8, cx - 4, cx, cx + 4, cx + 8, cx + 12)
        val hGaps = detectGapRows(bmp, minY, maxY, stripCols)

        val triV = bestTriple(vGaps)
        val triH = bestTriple(hGaps)

        val fallbackSide = maxOf(bboxW, bboxH)
        val bboxCx = (minX + maxX) / 2.0f
        val bboxCy = (minY + maxY) / 2.0f

        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (triV != null && triH != null) {
            val cellW = (triV.second - triV.first + triV.third - triV.second) / 2f
            val cellH = (triH.second - triH.first + triH.third - triH.second) / 2f
            left = triV.first - cellW
            right = triV.third + cellW
            top = triH.first - cellH
            bottom = triH.third + cellH
        } else {
            // Less confident fallback: a square around the beige region.
            left = bboxCx - fallbackSide / 2
            top = bboxCy - fallbackSide / 2
            right = bboxCx + fallbackSide / 2
            bottom = bboxCy + fallbackSide / 2
        }

        // Board is square — normalize to a centered square.
        val side = maxOf(right - left, bottom - top)
        val bcx = (left + right) / 2f
        val bcy = (top + bottom) / 2f
        var l = bcx - side / 2
        var t = bcy - side / 2
        var r = bcx + side / 2
        var b = bcy + side / 2

        // Small breathing room so the frame is included.
        val margin = side * 0.03f
        l -= margin; t -= margin; r += margin; b += margin

        l = l.coerceAtLeast(0f); t = t.coerceAtLeast(0f)
        r = r.coerceAtMost(w.toFloat()); b = b.coerceAtMost(h.toFloat())

        if (r - l < minScreen * 0.2f || b - t < minScreen * 0.2f) return null
        return RectF(l, t, r, b)
    }

    private fun detectGapColumns(bmp: Bitmap, x0: Int, x1: Int, rows: IntArray): List<Float> {
        val needed = (rows.size * 3 + 2) / 5 // majority of sampled rows
        val clusters = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (x in x0..x1) {
            var hits = 0
            for (y in rows) {
                val yy = y.coerceIn(0, bmp.height - 1)
                if (isFrame(bmp.getPixel(x, yy))) hits++
            }
            val gap = hits >= needed
            if (gap) {
                if (start < 0) start = x
            } else {
                if (start >= 0) {
                    clusters.add(start to x - 1)
                    start = -1
                }
            }
        }
        if (start >= 0) clusters.add(start to x1)
        return clusters.filter { it.second - it.first >= 2 }
            .map { (it.first + it.second) / 2f }
    }

    private fun detectGapRows(bmp: Bitmap, y0: Int, y1: Int, cols: IntArray): List<Float> {
        val needed = (cols.size * 3 + 2) / 5 // majority of sampled columns
        val clusters = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (y in y0..y1) {
            var hits = 0
            for (x in cols) {
                val xx = x.coerceIn(0, bmp.width - 1)
                if (isFrame(bmp.getPixel(xx, y))) hits++
            }
            val gap = hits >= needed
            if (gap) {
                if (start < 0) start = y
            } else {
                if (start >= 0) {
                    clusters.add(start to y - 1)
                    start = -1
                }
            }
        }
        if (start >= 0) clusters.add(start to y1)
        return clusters.filter { it.second - it.first >= 2 }
            .map { (it.first + it.second) / 2f }
    }

    /**
     * From a list of gap-line centers (the three interior gaps, possibly with
     * the outer frame edges mixed in), return the best evenly spaced triple.
     */
    private fun bestTriple(centers: List<Float>): Triple<Float, Float, Float>? {
        if (centers.size < 3) return null
        // Prefer a consecutive triple whose spacing is most equal (the three
        // interior grid lines are evenly spaced, unlike the outer frame edges).
        var best: Triple<Float, Float, Float>? = null
        var bestScore = Float.MAX_VALUE
        for (i in 0..centers.size - 3) {
            val a = centers[i]; val b = centers[i + 1]; val c = centers[i + 2]
            val d1 = b - a
            val d2 = c - b
            if (d1 <= 0 || d2 <= 0) continue
            val score = Math.abs(d1 - d2) / (d1 + d2)
            if (score < bestScore) {
                bestScore = score
                best = Triple(a, b, c)
            }
        }
        val t = best ?: return null
        val (a, b, c) = t
        val d1 = b - a
        val d2 = c - b
        val ratio = maxOf(d1, d2) / minOf(d1, d2)
        return if (ratio < 1.6f) t else null
    }

    private fun isBeige(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return isFrame(r, g, b) || (Math.abs(r - 205) <= TOL && Math.abs(g - 193) <= TOL && Math.abs(b - 180) <= TOL)
    }

    private fun isFrame(r: Int, g: Int, b: Int): Boolean =
        Math.abs(r - 187) <= TOL && Math.abs(g - 173) <= TOL && Math.abs(b - 160) <= TOL

    private fun isFrame(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return isFrame(r, g, b)
    }
}