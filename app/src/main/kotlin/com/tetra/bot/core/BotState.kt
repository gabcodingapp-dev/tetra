package com.tetra.bot.core

import android.graphics.Rect
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

    /** set by the UI to ask the bot to auto-locate the board from a fresh screenshot */
    val autoDetectRequested = MutableStateFlow(false)

    /**
     * The overlay window's current screen rect (null until the overlay is up).
     * The bot refuses to read or swipe while this overlaps the board's ROI, so
     * the robot can never "play itself" by capturing its own panel.
     */
    val overlayRect = MutableStateFlow<Rect?>(null)

    fun resetStats() {
        lastBoard.value = null
        maxTileValue.value = 0
        moves.value = 0
    }
}