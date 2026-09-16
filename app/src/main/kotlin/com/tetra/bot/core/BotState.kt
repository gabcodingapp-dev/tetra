package com.tetra.bot.core

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared mutable state between the overlay, the bot service and loose activities. */
object BotState {

    /** Lifecycle of the bot — the loop only moves turns while [Phase.RUNNING]. */
    enum class Phase { STOPPED, RUNNING, PAUSED }

    /** accessibility service connected */
    val connected = MutableStateFlow(false)

    /** robot lifecycle: stopped / actively swiping / paused */
    val phase = MutableStateFlow(Phase.STOPPED)

    /** a game-over board (or a stale board) was detected and the bot paused itself */
    val autoPaused = MutableStateFlow(false)

    /** last board read from screen, null until first successful read */
    val lastBoard = MutableStateFlow<ULong?>(null)

    val status = MutableStateFlow("Waiting…")
    val maxTileValue = MutableStateFlow(0)
    val moves = MutableStateFlow(0)

    /** bumped whenever the user changes strategy/speed so the bot rebuilds its solver */
    val configVersion = MutableStateFlow(0)

    fun resetStats() {
        lastBoard.value = null
        maxTileValue.value = 0
        moves.value = 0
    }
}