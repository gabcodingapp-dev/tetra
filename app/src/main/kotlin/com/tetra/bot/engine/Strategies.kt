package com.tetra.bot.engine

/** Open-addressing Long->Long map mirroring google::dense_hash_map semantics. */
class LongLongMap(initialCapacity: Int) {
    private val EMPTY = 0x1111111111111111L
    private val DELETED = 0x2222222222222222L

    private var keys: LongArray
    private var vals: LongArray
    private var mask: Int
    var size = 0
        private set

    init {
        var cap = 1
        while (cap < initialCapacity) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        vals = LongArray(cap)
        mask = cap - 1
    }

    private fun index(key: Long): Int {
        var h = key * -0x61C8864680B583EBL
        h = h xor (h ushr 33)
        h *= -0x70ABE48D3777AA87L
        return ((h xor (h ushr 32)) and mask.toLong()).toInt()
    }

    operator fun get(key: Long): Long? {
        var i = index(key)
        while (keys[i] != EMPTY) {
            if (keys[i] == key) return vals[i]
            i = (i + 1) and mask
        }
        return null
    }

    fun set(key: Long, value: Long) {
        var i = index(key)
        while (keys[i] != EMPTY && keys[i] != key) i = (i + 1) and mask
        val isNew = keys[i] == EMPTY
        keys[i] = key
        vals[i] = value
        if (isNew) {
            size++
            if (size * 10 > keys.size * 9) rehash(keys.size shl 1)
        }
    }

    fun erase(key: Long) {
        var i = index(key)
        while (keys[i] != EMPTY) {
            if (keys[i] == key) {
                keys[i] = DELETED
                vals[i] = 0
                size--
                if (size * 10 < keys.size * 3 && keys.size > 128) rehash(keys.size shr 1)
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun rehash(capacity: Int) {
        if (capacity < 128) return
        val oldKeys = keys
        val oldVals = vals
        keys = LongArray(capacity) { EMPTY }
        vals = LongArray(capacity)
        mask = capacity - 1
        size = 0
        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k != EMPTY && k != DELETED) {
                var j = index(k)
                while (keys[j] != EMPTY) j = (j + 1) and mask
                keys[j] = k
                vals[j] = oldVals[i]
                size++
            }
        }
    }

    fun clear() {
        keys = LongArray(keys.size) { EMPTY }
        vals = LongArray(keys.size)
        size = 0
    }
}

interface Solver {
    /** returns 0=left, 1=up, 2=right, 3=down */
    fun pickMove(board: ULong): Int
}

/**
 * Expectimax search with a cached transposition table. Ported from
 * ExpectimaxDepthStrategy.hpp + ExpectimaxStrategy.hpp. depth <= 0 uses the depth picker.
 */
class ExpectimaxSolver(val depth: Int, heuristicIdx: Int, val cacheLimit: Int = MAX_CACHE) : Solver {
    private val evaluator: (ULong) -> Long = Heuristics.ALL[heuristicIdx]

    private val cache = LongLongMap(USUAL_CACHE)
    private val deletionQueue = LongArray(MAX_CACHE)
    private var q0 = 0
    private var q1 = 0
    private var q2 = 0
    private var q3 = 0
    private var qEnd = 0

    override fun pickMove(board: ULong): Int {
        val d = if (depth <= 0) pickDepth(board) - depth else depth
        val move = (helper(board, d, 0) and 3L).toInt()
        updateCachePointers()
        return move
    }

    fun reset() {
        cache.clear()
        q0 = 0; q1 = 0; q2 = 0; q3 = 0; qEnd = 0
    }

    private fun helper(board: ULong, curDepth: Int, fours: Int): Long {
        if (Board.isGameOver(board)) {
            val score = MULT * evaluator(board)
            return (score - (score shr 2)) shl 2
        }
        if (curDepth == 0 || fours >= 4) {
            return (MULT * evaluator(board)) shl 2
        }

        if (curDepth >= CACHE_DEPTH) {
            val cached = cache.get(board.toLong())
            if (cached != null && (cached and 0xFL) >= curDepth) return cached shr 4
        }

        var bestScore = Heuristics.MIN_EVAL
        var bestMove = 0
        for (i in 0 until 4) {
            var expectedScore = 0L
            val newBoard = Board.makeMove(board, i)
            if (board == newBoard) {
                continue
            } else {
                val emptyMask = Board.toTileMask(newBoard)
                var j = 0
                while (j < 16) {
                    if (((emptyMask shr j) and 1) == 0) {
                        expectedScore += 9 * (helper(newBoard or (1uL shl (j shl 2)), curDepth - 1, fours) shr 2)
                        expectedScore += 1 * (helper(newBoard or (2uL shl (j shl 2)), curDepth - 1, fours + 1) shr 2)
                    }
                    j++
                }
                expectedScore /= (Board.countEmpty(emptyMask) * 10).toLong()
            }

            if (bestScore <= expectedScore) {
                bestScore = expectedScore
                bestMove = i
            }
        }

        if (curDepth >= CACHE_DEPTH) addToCache(board, bestScore, bestMove, curDepth)

        return (bestScore shl 2) or bestMove.toLong()
    }

    private fun addToCache(board: ULong, score: Long, move: Int, depth: Int) {
        cache.set(board.toLong(), (((score shl 2) or move.toLong()) shl 4) or depth.toLong())

        deletionQueue[qEnd and (MAX_CACHE - 1)] = board.toLong()
        qEnd++
        if (q0 + MAX_CACHE == qEnd) {
            cache.erase(deletionQueue[q0])
            q0++
            if (q0 >= MAX_CACHE) {
                q0 -= MAX_CACHE; q1 -= MAX_CACHE; q2 -= MAX_CACHE; q3 -= MAX_CACHE; qEnd -= MAX_CACHE
            }
        }

        if (cache.size > cacheLimit) {
            reset()
        }
    }

    private fun updateCachePointers() {
        while (q0 < q1) {
            cache.erase(deletionQueue[q0])
            q0++
            if (q0 >= MAX_CACHE) {
                q0 -= MAX_CACHE; q1 -= MAX_CACHE; q2 -= MAX_CACHE; q3 -= MAX_CACHE; qEnd -= MAX_CACHE
            }
        }
        q1 = maxOf(q0, q2)
        q2 = maxOf(q0, q3)
        q3 = qEnd
    }

    private fun pickDepth(board: ULong): Int {
        val tileCt = Board.countSet(Board.toTileMask(board))
        val score = Board.countDistinctTiles(board) + (if (tileCt <= 6) 0 else (tileCt - 6) shr 1)
        return 2 + (if (score >= 8) 1 else 0) + (if (score >= 11) 1 else 0) + (if (score >= 14) 1 else 0) +
                (if (score >= 15) 1 else 0) + (if (score >= 17) 1 else 0) + (if (score >= 19) 1 else 0)
    }

    companion object {
        const val CACHE_DEPTH = 2
        const val MAX_DEPTH = 10
        const val USUAL_CACHE = 1 shl 16
        const val MAX_CACHE = 1 shl 20

        val MULT: Long = (9e18 / (Heuristics.MAX_EVAL * 10 * 4 * 30 * 4 * 16)).toLong()
    }
}

/** Classic alpha-beta minimax. Ported from MinimaxStrategy.hpp. depth <= 0 uses the depth picker. */
class MinimaxSolver(val depth: Int, heuristicIdx: Int) : Solver {
    private val evaluator: (ULong) -> Long = Heuristics.ALL[heuristicIdx]

    override fun pickMove(board: ULong): Int {
        val d = if (depth <= 0) pickDepth(board) - depth else depth
        return (helper(board, d, Heuristics.MIN_EVAL, Heuristics.MAX_EVAL, 0) and 3L).toInt()
    }

    private fun helper(board: ULong, curDepth: Int, alphaIn: Long, beta0: Long, fours: Int): Long {
        var alpha = alphaIn
        if (Board.isGameOver(board)) {
            val score = evaluator(board)
            return score - (score shr 4)
        }
        if (curDepth == 0 || fours >= 5) {
            return evaluator(board) shl 2
        }

        var bestScore = Heuristics.MIN_EVAL
        var bestMove = 0
        for (i in 0 until 4) {
            var currentScore = Heuristics.MAX_EVAL
            val newBoard = Board.makeMove(board, i)
            if (board == newBoard) {
                continue
            } else {
                val tileMask = Board.toTileMask(newBoard)
                var beta = beta0
                var j = 0
                while (j < 16) {
                    if (((tileMask shr j) and 1) == 0) {
                        currentScore = minOf(
                            currentScore,
                            helper(newBoard or (1uL shl (j shl 2)), curDepth - 1, alpha, beta, fours) shr 2
                        )
                        currentScore = minOf(
                            currentScore,
                            helper(newBoard or (2uL shl (j shl 2)), curDepth - 1, alpha, beta, fours + 1) shr 2
                        )
                        beta = minOf(beta, currentScore)
                        if (currentScore < alpha) break
                    }
                    j++
                }
            }
            if (bestScore <= currentScore) {
                bestScore = currentScore
                bestMove = i
                alpha = maxOf(alpha, bestScore)
                if (bestScore > beta0) break
            }
        }

        return (bestScore shl 2) or bestMove.toLong()
    }

    private fun pickDepth(board: ULong): Int {
        val tileCt = Board.countSet(Board.toTileMask(board))
        val score = Board.countDistinctTiles(board) + (if (tileCt <= 6) 0 else (tileCt - 6) shr 1)
        return 2 + (if (score > 6) 1 else 0) + (if (score > 9) 1 else 0) + (if (score > 11) 1 else 0) +
                (if (score > 14) 1 else 0) + (if (score > 16) 1 else 0)
    }
}

/** Greedy: pick the valid move maximizing the heuristic of the resulting board. */
class GreedySolver(heuristicIdx: Int) : Solver {
    private val evaluator: (ULong) -> Long = Heuristics.ALL[heuristicIdx]
    override fun pickMove(board: ULong): Int {
        var bestMove = 0
        var bestScore = Long.MIN_VALUE
        for (i in 0 until 4) {
            val nb = Board.makeMove(board, i)
            if (nb == board) continue
            val s = evaluator(nb)
            if (s > bestScore) {
                bestScore = s
                bestMove = i
            }
        }
        return bestMove
    }
}

data class GameResult(val board: ULong, val moves: Int, val maxTileExp: Int)

/** Headless game simulator used by tests / offline checks. */
class GameSim(private val rnd: java.util.Random) {
    fun play(solver: Solver, maxMoves: Int = 200000): GameResult {
        var board = spawn(0uL)
        board = spawn(board)
        var moves = 0
        var maxTile = 1
        while (!Board.isGameOver(board) && moves < maxMoves) {
            val dir = solver.pickMove(board)
            val nb = Board.makeMove(board, dir)
            if (nb == board) break
            board = spawn(nb)
            val mt = Board.maxTile(board)
            if (mt > maxTile) maxTile = mt
            moves++
        }
        return GameResult(board, moves, maxTile)
    }

    fun spawn(b: ULong): ULong {
        val empty = IntArray(16)
        var n = 0
        for (i in 0 until 16) {
            if (((b shr (i shl 2)) and 0xFuL) == 0uL) empty[n++] = i
        }
        require(n > 0) { "board full" }
        val pos = empty[rnd.nextInt(n)]
        val isFour = rnd.nextInt(10) == 0
        return b or ((if (isFour) 2uL else 1uL) shl (pos shl 2))
    }
}

object Solvers {
    const val HEURISTIC_CORNER = 2
    const val HEURISTIC_FULL_WALL = 4
    const val HEURISTIC_MONOTONICITY = 7

    /** builder id -> factory used by the UI */
    fun create(id: String): Solver = when (id) {
        "expectimax_auto" -> ExpectimaxSolver(0, HEURISTIC_MONOTONICITY)
        "expectimax_3" -> ExpectimaxSolver(3, HEURISTIC_MONOTONICITY)
        "expectimax_2" -> ExpectimaxSolver(2, HEURISTIC_MONOTONICITY)
        "expectimax_wall" -> ExpectimaxSolver(0, HEURISTIC_FULL_WALL)
        "minimax" -> MinimaxSolver(0, HEURISTIC_MONOTONICITY)
        "greedy" -> GreedySolver(HEURISTIC_CORNER)
        else -> ExpectimaxSolver(0, HEURISTIC_MONOTONICITY)
    }

    val OPTIONS = listOf(
        "expectimax_auto" to "Expectimax (auto depth)",
        "expectimax_3" to "Expectimax (depth 3)",
        "expectimax_2" to "Expectimax (depth 2)",
        "expectimax_wall" to "Expectimax (wall)",
        "minimax" to "Minimax (auto)",
        "greedy" to "Greedy (corner)"
    )
}