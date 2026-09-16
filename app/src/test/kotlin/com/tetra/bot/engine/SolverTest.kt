package com.tetra.bot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {

    private fun boardWith(vararg cells: Triple<Int, Int, Int>): ULong {
        var b = 0uL
        for ((r, c, exp) in cells) {
            b = b or (exp.toULong() shl Board.nibbleOffset(r, c))
        }
        return b
    }

    @Test
    fun moveLeftMergesAdjacent() {
        val b = boardWith(Triple(0, 0, 1), Triple(0, 1, 1)) // row0: 2 2 . .
        val out = Board.makeMove(b, 0)
        assertEquals(2, Board.cellExponent(out, 0, 0))
        assertEquals(0, Board.cellExponent(out, 0, 1))
        assertEquals(0, Board.cellExponent(out, 0, 2))
        assertEquals(0, Board.cellExponent(out, 0, 3))
    }

    @Test
    fun moveRightDropsToRight() {
        val b = boardWith(Triple(0, 0, 1), Triple(0, 1, 1))
        val out = Board.makeMove(b, 2)
        assertEquals(0, Board.cellExponent(out, 0, 0))
        assertEquals(2, Board.cellExponent(out, 0, 3))
    }

    @Test
    fun moveUpMovesColumn() {
        val b = boardWith(Triple(0, 0, 1), Triple(1, 0, 1))
        val out = Board.makeMove(b, 1)
        assertEquals(2, Board.cellExponent(out, 0, 0))
        assertEquals(0, Board.cellExponent(out, 1, 0))
    }

    @Test
    fun moveDownMovesColumn() {
        val b = boardWith(Triple(0, 0, 1), Triple(1, 0, 1))
        val out = Board.makeMove(b, 3)
        assertEquals(2, Board.cellExponent(out, 3, 0))
    }

    @Test
    fun doubleMergeCreatesSingleTile() {
        val b = boardWith(Triple(1, 0, 1), Triple(1, 1, 1), Triple(1, 2, 1)) // 2 2 2 .
        val out = Board.makeMove(b, 0)
        // 2 2 2 . -> 4 2 . .
        assertEquals(2, Board.cellExponent(out, 1, 0))
        assertEquals(1, Board.cellExponent(out, 1, 1))
        assertEquals(0, Board.cellExponent(out, 1, 2))
    }

    @Test
    fun transposeIsInvolution() {
        val b = boardWith(Triple(0, 0, 5), Triple(1, 3, 3), Triple(3, 2, 7), Triple(2, 1, 11))
        assertEquals(b, Board.transpose(Board.transpose(b)))
    }

    @Test
    fun gameOverDetected() {
        // snake pattern with no merges possible, full board
        var b = 0uL
        // fill checkerboard: distinct adjacent values
        val rows = arrayOf(
            intArrayOf(1, 2, 3, 4),
            intArrayOf(4, 1, 2, 3),
            intArrayOf(3, 4, 1, 2),
            intArrayOf(2, 3, 4, 1)
        )
        for (r in 0..3) for (c in 0..3) {
            b = b or (rows[r][c].toULong() shl Board.nibbleOffset(r, c))
        }
        assertTrue(Board.isGameOver(b))
    }

    @Test
    fun tileMaskBasics() {
        val b = boardWith(Triple(0, 0, 1), Triple(1, 1, 1))
        val mask = Board.toTileMask(b)
        assertEquals(2, Board.countSet(mask))
        assertEquals(14, Board.countEmpty(mask))
    }

    @Test
    fun approxScore() {
        val b = boardWith(Triple(0, 0, 5)) // 32 tile
        assertEquals(32L * 4, Board.approxScore(b)) // (5-1)<<5 = 128
    }
}

class HeuristicsTest {
    @Test
    fun allHeuristicsRun() {
        var seed = java.util.Random(42)
        var rnd = java.util.Random(42)
        for (i in 0 until 500) {
            var b = 0uL
            for (cell in 0 until 16) {
                if (rnd.nextBoolean()) {
                    val exp = rnd.nextInt(12) + 1
                    b = b or (exp.toULong() shl (cell shl 2))
                }
            }
            for (h in Heuristics.ALL) {
                val v = h(b)
                assertTrue("non-negative eval", v >= 0)
            }
        }
    }
}

class LongLongMapTest {
    @Test
    fun basicOps() {
        val m = LongLongMap(64)
        m.set(1L, 100L)
        m.set(2L, 200L)
        assertEquals(100L, m.get(1L))
        assertEquals(200L, m.get(2L))
        m.erase(1L)
        assertEquals(null, m.get(1L))
        assertEquals(200L, m.get(2L))
        assertTrue(m.size == 1)
    }

    @Test
    fun grows() {
        val m = LongLongMap(16)
        for (i in 0 until 2000) {
            m.set(i.toLong(), i.toLong() * 7)
        }
        assertEquals(2000, m.size)
        for (i in 0 until 2000) {
            assertEquals(i.toLong() * 7, m.get(i.toLong()))
        }
    }
}

class SolverTest {
    @Test
    fun openersReturnValidMoves() {
        val solvers = listOf(
            ExpectimaxSolver(2, Solvers.HEURISTIC_CORNER),
            MinimaxSolver(2, Solvers.HEURISTIC_CORNER),
            GreedySolver(Solvers.HEURISTIC_CORNER)
        )
        for (s in solvers) {
            val sim = GameSim(java.util.Random(7))
            var b = sim.spawn(0uL)
            b = sim.spawn(b)
            val dir = s.pickMove(b)
            assertTrue("move $dir in 0..3", dir in 0..3)
            assertNotEquals("move changes board", b, Board.makeMove(b, dir))
        }
    }

    @Test
    fun expectimaxReaches128() {
        for (seed in 1..3) {
            val sim = GameSim(java.util.Random(seed.toLong()))
            val solver = ExpectimaxSolver(2, Solvers.HEURISTIC_FULL_WALL)
            val result = sim.play(solver, maxMoves = 2_000)
            println("seed=$seed maxTileExp=${result.maxTileExp} moves=${result.moves}")
            assertTrue("reached 128 (exp>=7), got ${result.maxTileExp}", result.maxTileExp >= 7)
        }
    }
}