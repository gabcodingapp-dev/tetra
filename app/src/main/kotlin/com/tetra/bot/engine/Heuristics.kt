package com.tetra.bot.engine

/**
 * Board evaluation heuristics, ported from heuristics.hpp.
 * All evaluations are non-negative.
 */
object Heuristics {
    const val MIN_EVAL = 0L
    val MAX_EVAL = 16L shl 41

    inline fun tileExp(b: ULong, r: Int, c: Int): Int = ((b shr (((r shl 2) or c) shl 2)) and 0xFuL).toInt()
    inline fun tileExpPos(b: ULong, pos: Int): Int = ((b shr pos) and 0xFuL).toInt()
    inline fun tileVal(b: ULong, r: Int, c: Int): Long {
        val e = tileExp(b, r, c)
        return if (e == 0) 0 else 1L shl e
    }

    fun scoreHeuristic(b: ULong): Long = Board.approxScore(b)

    fun mergeHeuristic(b: ULong): Long = Board.countEmpty(Board.toTileMask(b)).toLong()

    fun cornerHeuristic(b: ULong): Long {
        val lowerLeft = 10 * tileVal(b, 0, 3) + 5 * tileVal(b, 0, 2) + 2 * tileVal(b, 0, 1) + 1 * tileVal(b, 0, 0) +
                5 * tileVal(b, 1, 3) + 3 * tileVal(b, 1, 2) + 1 * tileVal(b, 1, 1) +
                2 * tileVal(b, 2, 3) + 1 * tileVal(b, 2, 2) +
                1 * tileVal(b, 3, 3)
        val upperLeft = 10 * tileVal(b, 3, 3) + 5 * tileVal(b, 3, 2) + 2 * tileVal(b, 3, 1) + 1 * tileVal(b, 3, 0) +
                5 * tileVal(b, 2, 3) + 3 * tileVal(b, 2, 2) + 1 * tileVal(b, 2, 1) +
                2 * tileVal(b, 1, 3) + 1 * tileVal(b, 1, 2) +
                1 * tileVal(b, 0, 3)
        val lowerRight = 10 * tileVal(b, 0, 0) + 5 * tileVal(b, 0, 1) + 2 * tileVal(b, 0, 2) + 1 * tileVal(b, 0, 3) +
                5 * tileVal(b, 1, 0) + 3 * tileVal(b, 1, 1) + 1 * tileVal(b, 1, 2) +
                2 * tileVal(b, 2, 0) + 1 * tileVal(b, 2, 1) +
                1 * tileVal(b, 3, 0)
        val upperRight = 10 * tileVal(b, 3, 0) + 5 * tileVal(b, 3, 1) + 2 * tileVal(b, 3, 2) + 1 * tileVal(b, 3, 3) +
                5 * tileVal(b, 2, 0) + 3 * tileVal(b, 2, 1) + 1 * tileVal(b, 2, 2) +
                2 * tileVal(b, 1, 0) + 1 * tileVal(b, 1, 1) +
                1 * tileVal(b, 0, 0)
        return maxOf(lowerLeft, upperLeft, lowerRight, upperRight)
    }

    private fun wallGapRaw(b: ULong): Long {
        val top =
            (tileExp(b, 3, 3).toLong() shl 40) or (tileExp(b, 3, 2).toLong() shl 36) or (tileExp(b, 3, 1).toLong() shl 32) or
            (tileExp(b, 2, 3).toLong() shl 20) or (tileExp(b, 2, 2).toLong() shl 24) or (tileExp(b, 2, 1).toLong() shl 28) or
            (tileExp(b, 1, 3).toLong() shl 16) or (tileExp(b, 1, 2).toLong() shl 12) or (tileExp(b, 1, 1).toLong() shl 8)
        val bottom =
            (tileExp(b, 0, 0).toLong() shl 40) or (tileExp(b, 0, 1).toLong() shl 36) or (tileExp(b, 0, 2).toLong() shl 32) or
            (tileExp(b, 1, 0).toLong() shl 20) or (tileExp(b, 1, 1).toLong() shl 24) or (tileExp(b, 1, 2).toLong() shl 28) or
            (tileExp(b, 2, 0).toLong() shl 16) or (tileExp(b, 2, 1).toLong() shl 12) or (tileExp(b, 2, 2).toLong() shl 8)
        val left =
            (tileExp(b, 0, 3).toLong() shl 40) or (tileExp(b, 1, 3).toLong() shl 36) or (tileExp(b, 2, 3).toLong() shl 32) or
            (tileExp(b, 0, 2).toLong() shl 20) or (tileExp(b, 1, 2).toLong() shl 24) or (tileExp(b, 2, 2).toLong() shl 28) or
            (tileExp(b, 0, 1).toLong() shl 16) or (tileExp(b, 1, 1).toLong() shl 12) or (tileExp(b, 2, 1).toLong() shl 8)
        val right =
            (tileExp(b, 3, 0).toLong() shl 40) or (tileExp(b, 2, 0).toLong() shl 36) or (tileExp(b, 1, 0).toLong() shl 32) or
            (tileExp(b, 3, 1).toLong() shl 20) or (tileExp(b, 2, 1).toLong() shl 24) or (tileExp(b, 1, 1).toLong() shl 28) or
            (tileExp(b, 3, 2).toLong() shl 16) or (tileExp(b, 2, 2).toLong() shl 12) or (tileExp(b, 1, 2).toLong() shl 8)
        return maxOf(top, bottom, left, right)
    }

    fun wallGapHeuristic(b: ULong): Long =
        maxOf(wallGapRaw(b), wallGapRaw(Board.transpose(b))) + scoreHeuristic(b)

    private fun fullWallRaw(b: ULong): Long {
        val top =
            (tileExp(b, 3, 3).toLong() shl 40) or (tileExp(b, 3, 2).toLong() shl 36) or (tileExp(b, 3, 1).toLong() shl 32) or (tileExp(b, 3, 0).toLong() shl 28) or
            (tileExp(b, 2, 3).toLong() shl 12) or (tileExp(b, 2, 2).toLong() shl 16) or (tileExp(b, 2, 1).toLong() shl 20) or (tileExp(b, 2, 0).toLong() shl 24) or
            (tileExp(b, 1, 3).toLong() shl 8)
        val bottom =
            (tileExp(b, 0, 0).toLong() shl 40) or (tileExp(b, 0, 1).toLong() shl 36) or (tileExp(b, 0, 2).toLong() shl 32) or (tileExp(b, 0, 3).toLong() shl 28) or
            (tileExp(b, 1, 0).toLong() shl 12) or (tileExp(b, 1, 1).toLong() shl 16) or (tileExp(b, 1, 2).toLong() shl 20) or (tileExp(b, 1, 3).toLong() shl 24) or
            (tileExp(b, 2, 0).toLong() shl 8)
        val left =
            (tileExp(b, 0, 3).toLong() shl 40) or (tileExp(b, 1, 3).toLong() shl 36) or (tileExp(b, 2, 3).toLong() shl 32) or (tileExp(b, 3, 3).toLong() shl 28) or
            (tileExp(b, 0, 2).toLong() shl 12) or (tileExp(b, 1, 2).toLong() shl 16) or (tileExp(b, 2, 2).toLong() shl 20) or (tileExp(b, 3, 2).toLong() shl 24) or
            (tileExp(b, 0, 1).toLong() shl 8)
        val right =
            (tileExp(b, 3, 0).toLong() shl 40) or (tileExp(b, 2, 0).toLong() shl 36) or (tileExp(b, 1, 0).toLong() shl 32) or (tileExp(b, 0, 0).toLong() shl 28) or
            (tileExp(b, 3, 1).toLong() shl 12) or (tileExp(b, 2, 1).toLong() shl 16) or (tileExp(b, 1, 1).toLong() shl 20) or (tileExp(b, 0, 1).toLong() shl 24) or
            (tileExp(b, 3, 2).toLong() shl 8)
        return maxOf(top, bottom, left, right)
    }

    fun fullWallHeuristic(b: ULong): Long =
        maxOf(fullWallRaw(b), fullWallRaw(Board.transpose(b))) + scoreHeuristic(b)

    private fun valCmp(a: Int, b: Int): Long = (1L shl a) * if (a <= b) 1 else -1

    private fun strictWallRaw(boardIn: ULong, maxTile: Int): Long {
        val board = boardIn
        if ((board and 0xFuL).toInt() < maxTile) return Board.countEmpty(Board.toTileMask(board)).toLong()

        val idxs = intArrayOf(0, 4, 8, 12, 28, 24, 20, 16)
        var mx = maxOf(
            ((board shr 32) and 0xFuL).toInt(), ((board shr 36) and 0xFuL).toInt(),
            ((board shr 40) and 0xFuL).toInt(), ((board shr 44) and 0xFuL).toInt(),
            ((board shr 48) and 0xFuL).toInt(), ((board shr 52) and 0xFuL).toInt(),
            ((board shr 56) and 0xFuL).toInt(), ((board shr 60) and 0xFuL).toInt()
        )
        var inv = -1
        var ret = maxTile.toLong() shl 32
        for (i in 7 downTo 0) {
            val v = ((board shr idxs[i]) and 0xFuL).toInt()
            if (v < mx) {
                inv = idxs[i]
                ret = (maxTile.toLong() shl 32) - ((mx - v).toLong() shl (4 * (7 - i)))
            } else {
                mx = v
                ret += v.toLong() shl (4 * (7 - i))
            }
        }
        ret = ret shl 9

        if (inv != -1) {
            val invVal = ((board shr inv) and 0xFuL).toInt()
            if ((inv and 0b1100) != 0b1100) {
                if (inv < 16) ret += valCmp(((board shr (inv + 4)) and 0xFuL).toInt(), invVal)
            }
            ret += valCmp(((board shr (inv + 16)) and 0xFuL).toInt(), invVal)
            if ((inv and 0b1100) != 0) {
                if (inv >= 16) ret += valCmp(((board shr (inv - 4)) and 0xFuL).toInt(), invVal)
            }
        } else {
            ret += valCmp(((board shr 32) and 0xFuL).toInt(), ((board shr 16) and 0xFuL).toInt()) +
                    valCmp(((board shr 36) and 0xFuL).toInt(), ((board shr 20) and 0xFuL).toInt()) +
                    valCmp(((board shr 40) and 0xFuL).toInt(), ((board shr 24) and 0xFuL).toInt()) +
                    valCmp(((board shr 44) and 0xFuL).toInt(), ((board shr 28) and 0xFuL).toInt())
        }
        return ret
    }

    fun strictWallHeuristic(b: ULong): Long {
        val maxTile = Board.maxTile(b)
        val fh = Board.flipH(b)
        val fv = Board.flipV(b)
        val fvh = Board.flipV(fh)
        return maxOf(
            strictWallRaw(b, maxTile), strictWallRaw(Board.transpose(b), maxTile),
            strictWallRaw(fh, maxTile), strictWallRaw(Board.transpose(fh), maxTile),
            strictWallRaw(fv, maxTile), strictWallRaw(Board.transpose(fv), maxTile),
            strictWallRaw(fvh, maxTile), strictWallRaw(Board.transpose(fvh), maxTile),
            0L
        )
    }

    private fun skewedCornerRaw(b: ULong): Long {
        val top =
            16 * tileVal(b, 3, 3) + 10 * tileVal(b, 3, 2) + 6 * tileVal(b, 3, 1) + 3 * tileVal(b, 3, 0) +
            10 * tileVal(b, 2, 3) + 6 * tileVal(b, 2, 2) + 3 * tileVal(b, 2, 1) + 1 * tileVal(b, 2, 0) +
            4 * tileVal(b, 1, 3) + 3 * tileVal(b, 1, 2) + 1 * tileVal(b, 1, 1) +
            1 * tileVal(b, 0, 3) + 1 * tileVal(b, 0, 2)
        val bottom =
            16 * tileVal(b, 0, 0) + 10 * tileVal(b, 0, 1) + 6 * tileVal(b, 0, 2) + 3 * tileVal(b, 0, 3) +
            10 * tileVal(b, 1, 0) + 6 * tileVal(b, 1, 1) + 3 * tileVal(b, 1, 2) + 1 * tileVal(b, 1, 3) +
            4 * tileVal(b, 2, 0) + 3 * tileVal(b, 2, 1) + 1 * tileVal(b, 2, 2) +
            1 * tileVal(b, 3, 0) + 1 * tileVal(b, 3, 1)
        val left =
            16 * tileVal(b, 0, 3) + 10 * tileVal(b, 1, 3) + 6 * tileVal(b, 2, 3) + 3 * tileVal(b, 3, 3) +
            10 * tileVal(b, 0, 2) + 6 * tileVal(b, 1, 2) + 3 * tileVal(b, 2, 2) + 1 * tileVal(b, 3, 2) +
            4 * tileVal(b, 0, 1) + 3 * tileVal(b, 1, 1) + 1 * tileVal(b, 2, 1) +
            1 * tileVal(b, 0, 0) + 1 * tileVal(b, 1, 0)
        val right =
            16 * tileVal(b, 3, 0) + 10 * tileVal(b, 2, 0) + 6 * tileVal(b, 1, 0) + 3 * tileVal(b, 0, 0) +
            10 * tileVal(b, 3, 1) + 6 * tileVal(b, 2, 1) + 3 * tileVal(b, 1, 1) + 1 * tileVal(b, 0, 1) +
            4 * tileVal(b, 3, 2) + 3 * tileVal(b, 2, 2) + 1 * tileVal(b, 1, 2) +
            1 * tileVal(b, 3, 3) + 1 * tileVal(b, 2, 3)
        return maxOf(top, bottom, left, right)
    }

    fun skewedCornerHeuristic(b: ULong): Long =
        maxOf(skewedCornerRaw(b), skewedCornerRaw(Board.transpose(b)))

    val monotonicity: LongArray = LongArray(Board.ROWS).apply {
        for (row in 0 until Board.ROWS) {
            val r = intArrayOf(
                (row shr 12) and 0xF, (row shr 8) and 0xF, (row shr 4) and 0xF, row and 0xF
            )
            var mono = (1L shl r[0]) + (1L shl r[1]) + (1L shl r[2]) + (1L shl r[3])
            for (i in 0 until 3) {
                if (r[i] < r[i + 1]) {
                    if (r[i] == 0) mono -= 1L shl (3 * r[i + 1] / 2)
                    else mono -= 1L shl (3 * r[i + 1] - 2 * r[i])
                }
            }
            this[row] = mono
        }
        for (row in 0 until Board.ROWS) {
            this[row] = maxOf(this[row], this[Board.REVERSED[row]])
        }
    }

    fun monotonicityHeuristic(b: ULong): Long {
        val tb = Board.transpose(b)
        val rowMax = maxOf(
            monotonicity[(b shr 48).toInt() and 0xFFFF],
            monotonicity[b.toInt() and 0xFFFF],
            monotonicity[(tb shr 48).toInt() and 0xFFFF],
            monotonicity[tb.toInt() and 0xFFFF]
        ) * 8
        return maxOf(
            0L,
            monotonicity[(b shr 48).toInt() and 0xFFFF] +
                monotonicity[(b shr 32).toInt() and 0xFFFF] +
                monotonicity[(b shr 16).toInt() and 0xFFFF] +
                monotonicity[b.toInt() and 0xFFFF] +
                monotonicity[(tb shr 48).toInt() and 0xFFFF] +
                monotonicity[(tb shr 32).toInt() and 0xFFFF] +
                monotonicity[(tb shr 16).toInt() and 0xFFFF] +
                monotonicity[tb.toInt() and 0xFFFF] +
                rowMax
        ) + Board.countEmpty(Board.toTileMask(b))
    }

    val ALL: Array<(ULong) -> Long> = arrayOf(
        ::scoreHeuristic,
        ::mergeHeuristic,
        ::cornerHeuristic,
        ::wallGapHeuristic,
        ::fullWallHeuristic,
        ::strictWallHeuristic,
        ::skewedCornerHeuristic,
        ::monotonicityHeuristic
    )

    val NAMES = arrayOf(
        "Score", "Merge", "Corner", "Wall gap", "Full wall", "Strict wall", "Skewed corner", "Monotonicity"
    )
}