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
        val minSideFrac = 0.20f

        // ---- 1) bounding box of beige (frame + empty tile) pixels ----
        // Scan only the lower two thirds of the screen: on a classic 2048 page
        // the top carries the beige title/score boxes, which would otherwise
        // inflate the box beyond the actual grid (this is what made "Too big"
        // and broke auto-align previously).
        val step = (minOf(w, h) / 220).coerceIn(2, 8)
        val yLow = (h * (if (relaxed) 0.24f else 0.33f)).toInt()
        val xEdge = (w * 0.015f).toInt()
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var count = 0
        for (y in yLow until h step step) {
            for (x in xEdge until w - xEdge step step) {
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

        // ---- 2) locate the 3 interior vertical gap lines ----
        val cy = (minY + maxY) / 2
        val stripRows = intArrayOf(cy - 12, cy - 8, cy - 4, cy, cy + 4, cy + 8, cy + 12)
        val vGaps = detectGapColumns(bmp, minX, maxX, stripRows)

        // ---- 3) locate the 3 interior horizontal gap lines ----
        // Sample the full board width: classic 2048 renders the horizontal
        // separators within the vertical gaps, which a narrow column strip can
        // miss (observed on real screens). Full-width rows are unambiguous.
        val hGaps = detectGapRows(bmp, minY, maxY, minX, maxX)

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
            // Less confident fallback: a square around the beige region, but only
            // when the region is near-square — a long strip is almost certainly
            // not a 2048 board.
            val aspect = maxOf(bboxW, bboxH) / minOf(bboxW, bboxH).toFloat()
            if (aspect > 1.35f) return null
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

    private fun detectGapRows(bmp: Bitmap, y0: Int, y1: Int, x0: Int, x1: Int): List<Float> {
        val gStep = (bmp.width / 170).coerceIn(3, 10)
        val total = (x1 - x0) / gStep + 1
        val needed = (total * 2) / 5 // frame line should cover the row almost fully
        val clusters = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (y in y0..y1) {
            var hits = 0
            for (x in x0..x1 step gStep) {
                if (isFrame(bmp.getPixel(x, y))) hits++
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
        // Prefer a consecutive triple whose spacing is most equal. Outer frame
        // edges are ~equally spaced from the interior lines too, so among
        // equally spaced triples prefer the one centered on the cluster (the
        // true board's interior lines sit in the middle of the gap-rows list).
        val listMid = (centers.first() + centers.last()) / 2f
        val listSpan = listMid - centers.first()
        var best: Triple<Float, Float, Float>? = null
        var bestScore = Float.MAX_VALUE
        for (i in 0..centers.size - 3) {
            val a = centers[i]; val b = centers[i + 1]; val c = centers[i + 2]
            val d1 = b - a
            val d2 = c - b
            if (d1 <= 0 || d2 <= 0) continue
            val spacingScore = Math.abs(d1 - d2) / (d1 + d2)
            val bal = Math.abs(b - listMid) / (listSpan + 1f) * 0.01f
            val score = spacingScore + bal
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

    private fun isBeige(pixel: Int, tol: Int = TOL): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return isFrame(r, g, b, tol) ||
            (Math.abs(r - 205) <= tol && Math.abs(g - 193) <= tol && Math.abs(b - 180) <= tol)
    }

    private fun isFrame(r: Int, g: Int, b: Int, tol: Int = TOL): Boolean =
        Math.abs(r - 187) <= tol && Math.abs(g - 173) <= tol && Math.abs(b - 160) <= tol

    private fun isFrame(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return isFrame(r, g, b)
    }
}