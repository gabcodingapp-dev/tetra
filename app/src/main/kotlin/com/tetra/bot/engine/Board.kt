package com.tetra.bot.engine

/**
 * Bit-packed 2048 board. Each cell is a 4-bit exponent: 0 = empty, n = 2^n.
 * 16 cells -> 64 bits. Layout (visual): nibble offset = (3 - r) * 16 + (3 - c) * 4.
 * Ported from https://github.com/qpwoeirut/2048-solver (util.hpp / game.hpp).
 */
object Board {
    const val ROWS = 0x10000

    /** result of shifting a single row left with merging */
    val SHIFT: IntArray = IntArray(ROWS) { row ->
        val r = intArrayOf(
            (row shr 12) and 0xF,
            (row shr 8) and 0xF,
            (row shr 4) and 0xF,
            row and 0xF
        )
        pullLeft(r)
        if (r[0] > 0 && r[0] == r[1]) { r[0]++; r[1] = 0 }
        if (r[1] > 0 && r[1] == r[2]) { r[1]++; r[2] = 0 }
        if (r[2] > 0 && r[2] == r[3]) { r[2]++; r[3] = 0 }
        pullLeft(r)
        (minOf(r[0], 15) shl 12) or (minOf(r[1], 15) shl 8) or (minOf(r[2], 15) shl 4) or minOf(r[3], 15)
    }

    /** precomputed nibble-reversed row */
    val REVERSED: IntArray = IntArray(ROWS) { row ->
        ((row and 0xF) shl 12) or (((row shr 4) and 0xF) shl 8) or (((row shr 8) and 0xF) shl 4) or (row shr 12)
    }

    private fun pullLeft(r: IntArray) {
        for (i in 0 until 3) {
            if (r[0] == 0 && r[1] > 0) { r[0] = r[1]; r[1] = 0 }
            if (r[1] == 0 && r[2] > 0) { r[1] = r[2]; r[2] = 0 }
            if (r[2] == 0 && r[3] > 0) { r[2] = r[3]; r[3] = 0 }
        }
    }

    /** 16-bit mask where bit j = 1 iff nibble j is non-empty */
    fun toTileMask(board: ULong): Int {
        var m = board
        m = (m or (m shr 1) or (m shr 2) or (m shr 3)) and 0x1111111111111111uL
        m = (m or (m shr 3) or (m shr 6) or (m shr 9)) and 0x0F000F000F000FuL
        m = (m or (m shr 12) or (m shr 24) or (m shr 36)) and 0xFFFFuL
        return m.toInt()
    }

    fun transpose(b: ULong): ULong {
        val a = ((b and 0x0000F0F00000F0F0uL) shl 12) or
                ((b and 0xF0F00F0FF0F00F0FuL) or ((b and 0x0F0F00000F0F0000uL) shr 12))
        return ((a and 0x00000000FF00FF00uL) shl 24) or
                (a and 0xFF00FF0000FF00FFuL) or
                ((a and 0x00FF00FF00000000uL) shr 24)
    }

    fun flipH(b: ULong): ULong =
        (REVERSED[(b shr 48).toInt() and 0xFFFF].toULong() shl 48) or
        (REVERSED[(b shr 32).toInt() and 0xFFFF].toULong() shl 32) or
        (REVERSED[(b shr 16).toInt() and 0xFFFF].toULong() shl 16) or
        REVERSED[b.toInt() and 0xFFFF].toULong()

    fun flipV(b: ULong): ULong =
        ((b and 0xFFFFuL) shl 48) or
        (((b shr 16) and 0xFFFFuL) shl 32) or
        (((b shr 32) and 0xFFFFuL) shl 16) or
        ((b shr 48) and 0xFFFFuL)

    /** dir: 0=left, 1=up, 2=right, 3=down */
    fun makeMove(b: ULong, dir: Int): ULong {
        var board = b
        if ((dir and 1) == 1) board = transpose(board)
        if (dir >= 2) board = flipH(board)
        board = (SHIFT[(board shr 48).toInt() and 0xFFFF].toULong() shl 48) or
                (SHIFT[(board shr 32).toInt() and 0xFFFF].toULong() shl 32) or
                (SHIFT[(board shr 16).toInt() and 0xFFFF].toULong() shl 16) or
                SHIFT[board.toInt() and 0xFFFF].toULong()
        if (dir >= 2) board = flipH(board)
        if ((dir and 1) == 1) board = transpose(board)
        return board
    }

    fun isGameOver(b: ULong): Boolean =
        b == makeMove(b, 0) && b == makeMove(b, 1) && b == makeMove(b, 2) && b == makeMove(b, 3)

    fun countEmpty(mask: Int): Int {
        var empty = 16
        var m = mask
        while (m > 0) {
            m = m and (m - 1)
            --empty
        }
        return empty
    }

    fun countSet(mask: Int): Int = 16 - countEmpty(mask)

    fun countDistinctTiles(b: ULong): Int {
        var exists = 0
        var i = 0
        while (i < 64) {
            exists = exists or (1 shl ((b shr i) and 0xFuL).toInt())
            i += 4
        }
        exists = exists and 0xFFFE
        return countSet(exists)
    }

    /** highest exponent present */
    fun maxTile(b: ULong): Int {
        var max = 0
        var i = 0
        while (i < 64) {
            val v = ((b shr i) and 0xFuL).toInt()
            if (v > max) max = v
            i += 4
        }
        return max
    }

    /** board-derived score, assuming all spawns were 2's */
    fun approxScore(b: ULong): Long {
        var score = 0L
        var i = 0
        while (i < 64) {
            val tile = ((b shr i) and 0xFuL).toInt()
            if (tile > 1) score += (tile - 1).toLong() shl tile
            i += 4
        }
        return score
    }

    /** write nibble for visual cell (r, c) */
    fun nibbleOffset(r: Int, c: Int): Int = (3 - r) * 16 + (3 - c) * 4

    fun cellExponent(b: ULong, r: Int, c: Int): Int = ((b shr nibbleOffset(r, c)) and 0xFuL).toInt()

    fun exponentAtPos(b: ULong, pos: Int): Int = ((b shr (pos shl 2)) and 0xFuL).toInt()
}