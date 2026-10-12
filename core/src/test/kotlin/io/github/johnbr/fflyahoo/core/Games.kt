package io.github.johnbr.fflyahoo.core

import io.github.johnbr.fflyahoo.core.relay.GameState

/** Detroit (8) at Buffalo (2), the clubs the Python tests use. */
const val DET = "8"
const val BUF = "2"

/** A games-feed `g` row as the relay writes it. */
fun gameRow(
    team: String = DET,
    down: Int = 1,
    distance: Int = 10,
    toGoal: Int = 75,
    away: Int = 0,
    home: Int = 0,
    state: String = "in",
    clock: String = "",
    period: String = "2",
    gameId: String = "g1",
): GameState {
    val status = when (state) {
        "in" -> "P"
        "post" -> "F"
        "pre" -> "S"
        "delayed" -> "D"
        else -> "?"
    }
    val cells = MutableList(16) { "" }
    cells[0] = "g"
    cells[1] = gameId
    cells[2] = DET
    cells[3] = BUF
    cells[4] = status
    cells[6] = period
    cells[7] = clock
    cells[8] = away.toString()
    cells[9] = home.toString()
    cells[10] = "0"
    cells[11] = down.toString()
    cells[12] = distance.toString()
    cells[13] = toGoal.toString()
    cells[14] = team
    return GameState(cells)
}
