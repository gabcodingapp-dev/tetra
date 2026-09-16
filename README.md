# Tetra — 2048 Auto-Player (floating bot)

Tetra is an Android accessibility bot that **plays 2048 for you**. It reads the board from your
screen, picks the best move with an AI strategy, and swipes on the real game — all controlled from
a small floating bubble that hovers above any app.

The AI is a faithful Kotlin port of [qpwoeirut/2048-solver](https://github.com/qpwoeirut/2048-solver)
(bit-packed board, expectimax + transposition cache, minimax, greedy, 8 heuristics).

## How it works

1. You open your 2048 game (website or app) and start a new game.
2. Tetra's **floating bubble** shows a control panel: pause/resume, speed, strategy, and alignment.
3. Tap **⌖ Align** to drag a square over the 4×4 board once.
4. The bot captures the screen, classifies each cell's color into a tile value, decides with the AI,
   and injects a swipe gesture (via the accessibility service). Repeat until game over.

## Permissions

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the floating control bubble over other apps (granted manually in *Settings → Apps → Display over other apps*). |
| Accessibility service | Read the screen (screenshots) and inject swipe gestures. Enabled manually in *Settings → Accessibility*. |
| `FOREGROUND_SERVICE` + `specialUse` | Keep the bot + bubble alive. |
| `POST_NOTIFICATIONS` | Show the control notification. |

No internet, location, or contacts access. Source is open; nothing leaves the device.

## Building

The GitHub Actions workflow builds `app-debug.apk` on every push to `main`:

```
.github/workflows/build.yml
```

Download the artifact from the **Actions** tab of the repo. Or build locally:

```bash
gradle testDebugUnitTest assembleDebug
```

## Usage tips

- Works best with the **classic 2048 color palette** (gabrielecirulli style).
- Keep the bubble's square aligned with the actual board; recalibrate if the window resizes.
- The bot pauses itself when it detects a game-over board. Tap **New Game** in the game, then Resume.
- Choose a lower depth (e.g. *Expectimax depth 2*) on slow phones — the depth picker is adaptive.

## Credits & license

MIT © 2026. The solver algorithms are a port of the MIT-licensed
[2048-solver](https://github.com/qpwoeirut/2048-solver) by qpwoeirut. See `LICENSE` and the
`NOTICE` in the repo for attribution.