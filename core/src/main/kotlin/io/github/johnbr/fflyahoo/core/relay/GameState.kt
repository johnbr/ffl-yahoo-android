package io.github.johnbr.fflyahoo.core.relay

import io.github.johnbr.fflyahoo.core.Py

/** One NFL game as the relay's `g` row reports it, in the terms the screens want. */
class GameState(private val cells: List<String>) {
    private fun get(i: Int) = Relay.cell(cells, i)

    /** `{club id: points per period played}`, from the `h` rows — see [Relay.parseGames]. */
    val quarters: MutableMap<String, List<Int>> = LinkedHashMap()
    val gameId = get(GameRow.GAME_ID)
    val away = get(GameRow.AWAY)
    val home = get(GameRow.HOME)

    // Mutable only so settleForgottenGames can mark a game the relay forgot as final.
    var status = get(GameRow.STATUS)
    val period = get(GameRow.PERIOD)
    val clock = get(GameRow.CLOCK)
    var awayScore = get(GameRow.AWAY_SCORE)
    var homeScore = get(GameRow.HOME_SCORE)
    val startTime = get(GameRow.START_TIME)
    val down = get(GameRow.DOWN)
    val distance = get(GameRow.DISTANCE)
    val yardsToGoal = get(GameRow.YARDS_TO_GOAL)
    val teamWithBall = get(GameRow.TEAM_WITH_BALL)

    /** `playsId` is the home team id for NFL. */
    val playsId = get(GameRow.HOME)

    /** `pre` / `in` / `post` / `delayed` / `unknown`. */
    val state: String get() = Stats.GAME_STATUS[status] ?: "unknown"

    val atHalftime: Boolean get() = Relay.isHalftime(period, clock)

    /** Possession, only while the game is actually running and not at half time. */
    fun hasBall(teamId: String): Boolean =
        state == "in" && !atHalftime && teamId.isNotEmpty() && teamId == teamWithBall

    /** Possession inside the opponent's 20. */
    fun inRedZone(teamId: String): Boolean {
        if (!hasBall(teamId)) return false
        val yards = Py.intOrNull(yardsToGoal) ?: return false
        return yards in 1..RED_ZONE_YARDS
    }

    /** The game blurb printed beside a player, from that team's side. */
    fun noteFor(teamId: String): String {
        val isHome = teamId == home
        val opponent = Relay.teamAbbr(if (isHome) away else home)
        val versus = if (isHome) "vs $opponent" else "@ $opponent"
        val mine = if (isHome) homeScore else awayScore
        val theirs = if (isHome) awayScore else homeScore
        return when (state) {
            "pre" -> versus
            "in" -> "${Relay.clockText(period, clock)} $mine-$theirs $versus"
            "delayed" -> "Delayed $mine-$theirs $versus"
            "post" -> {
                if (mine.isEmpty() || theirs.isEmpty()) return "Final $versus"
                val m = Py.intOrNull(mine)
                val t = Py.intOrNull(theirs)
                val result = if (m == null || t == null) "" else if (m > t) "W" else if (m < t) "L" else "T"
                "Final $result $mine-$theirs $versus".replace("  ", " ")
            }
            else -> versus
        }
    }

    companion object {
        /** `yardsToGoal` counts down toward the OPPONENT'S goal line, so "inside the 20" is `<= 20`. */
        const val RED_ZONE_YARDS = 20
    }
}

/** `g` — one row per NFL game. */
internal object GameRow {
    const val GAME_ID = 1
    const val AWAY = 2
    const val HOME = 3
    const val STATUS = 4
    const val PERIOD = 6
    const val CLOCK = 7
    const val AWAY_SCORE = 8
    const val HOME_SCORE = 9
    const val START_TIME = 10
    const val DOWN = 11
    const val DISTANCE = 12
    const val YARDS_TO_GOAL = 13
    const val TEAM_WITH_BALL = 14
}
