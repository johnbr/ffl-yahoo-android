package io.github.johnbr.fflyahoo.core.live

import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.relay.GameState
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.relay.RelayPlay
import io.github.johnbr.fflyahoo.core.state.LeagueState
import io.github.johnbr.fflyahoo.core.state.NflPlayRow

/**
 * A play's result, read off the games feed before Yahoo's text arrives. Port of `pending_plays.py`.
 *
 * Yahoo moves a game's down, distance and spot 2-30 s before the play's sentence reaches the play feed.
 * The snapshots either side of the snap already say most of what happened — "Gain of 7, 1st down",
 * "Turnover, Buf ball", "Det touchdown" — and that is shown, marked provisional, until the text lands.
 * It cannot name the player or tell a penalty from a gain.
 */
object Pending {
    /** How long after a games-feed change a further change is still the SAME play. */
    const val REFINE_SECONDS = 18.0

    /** Play-feed rows that never move the ball: `24` timeout / two-minute warning, `25` end of a period. */
    val NO_BALL_TYPES = setOf("24", "25")

    /** How long the play feed is re-checked for a pending play's text. */
    const val CHASE_FOR_SECONDS = 60.0

    private fun int(value: String?): Int = (if (value.isNullOrEmpty()) 0 else Py.intOrNull(value)) ?: 0

    fun snapOf(game: GameState) = Snap(
        team = game.teamWithBall,
        down = int(game.down),
        distance = int(game.distance),
        toGoal = int(game.yardsToGoal),
        awayScore = Py.intOrNull(game.awayScore),
        homeScore = Py.intOrNull(game.homeScore),
    )

    /** `(period, seconds elapsed in it)` — orderable, unlike a clock that runs down. */
    fun gameTime(period: String?, clock: String?): GameTime? {
        val parts = (clock ?: "").split(":")
        if (parts.size != 2) return null
        val p = Py.intOrNull(period) ?: return null
        val m = Py.intOrNull(parts[0]) ?: return null
        val s = Py.intOrNull(parts[1]) ?: return null
        return GameTime(p, 15 * 60 - (m * 60 + s))
    }

    /** The situation a play-feed row ran from. */
    fun situationOf(play: RelayPlay) = Situation(play.teamWithBall, int(play.down), int(play.distance), int(play.yardsToGoal))

    /** `(club, points)` if exactly one side's score went up. */
    internal fun scored(before: Snap, after: Snap, away: String, home: String): Pair<String, Int>? {
        val ba = before.awayScore ?: return null
        val bh = before.homeScore ?: return null
        val aa = after.awayScore ?: return null
        val ah = after.homeScore ?: return null
        val awayGain = aa - ba
        val homeGain = ah - bh
        if (awayGain > 0 && homeGain <= 0) return away to awayGain
        if (homeGain > 0 && awayGain <= 0) return home to homeGain
        return null
    }

    /**
     * What one games-feed change says happened, or `""`. [before] is the situation the play was run
     * from; [kickoff] says a score came since the ball last changed hands.
     */
    fun describeChange(before: Snap, after: Snap, away: String, home: String, kickoff: Boolean = false): String {
        scored(before, after, away, home)?.let { (club, points) ->
            val abbr = Relay.teamAbbr(club)
            return when {
                points >= 6 -> "$abbr touchdown"
                points == 3 -> "$abbr field goal"
                points == 2 -> if (club != before.team) "$abbr safety" else "$abbr two-point conversion"
                points == 1 -> "$abbr extra point"
                else -> "$abbr +$points"
            }
        }
        if (!(before.hasSpot(away, home) && after.hasSpot(away, home))) return ""

        if (after.team != before.team) {
            val abbr = Relay.teamAbbr(after.team)
            if (kickoff) return "Kickoff, $abbr ball"
            if (before.down == 1 && before.distance == 10) return "Change of possession, $abbr ball"
            if (before.down == 4) {
                val moved = before.toGoal + after.toGoal - 100
                val what = if (moved >= 20) "Punt" else "Change of possession"
                return "$what, $abbr ball"
            }
            if (before.down != 0) return "Turnover, $abbr ball"
            return "Change of possession, $abbr ball"
        }

        val spotGain = before.toGoal - after.toGoal
        if (after.down == 1 && (before.down != 1 || after.distance == before.distance)) {
            if (spotGain >= before.distance) return "${yards(spotGain)}, 1st down"
            if (before.down != 1) return "1st down" // the spot has not caught up yet
            return "" // 1st & 10 again a yard over: a re-spot, not a play
        }
        if (after.down == before.down + 1) return yards(before.distance - after.distance)
        if (after.down == before.down && after.distance != before.distance) return yards(before.distance - after.distance)
        return ""
    }

    private fun yards(gained: Int): String = when {
        gained > 0 -> "Gain of $gained"
        gained < 0 -> "Loss of ${-gained}"
        else -> "No gain"
    }

    /** Whether [play] is the one snapped from [origin]: by recorded situation, else by the clock. */
    internal fun ranFrom(play: Landed, origin: Snap, originAt: GameTime?): Boolean {
        if (play.situation == origin.situation) return true
        val snapped = gameTime(play.period, play.clock)
        return snapped != null && originAt != null && snapped >= originAt
    }
}

/** One game's state as the games feed reports it at one poll. */
data class Snap(
    /** Club with the ball. `"0"` when nobody has it. */
    val team: String,
    val down: Int,
    val distance: Int,
    /** Yards to the opponent's goal line; 0 between plays and after a score. */
    val toGoal: Int,
    val awayScore: Int?,
    val homeScore: Int?,
) {
    val situation: Situation get() = Situation(team, down, distance, toGoal)

    /** A real ball on the field, held by one of the two clubs playing. */
    fun hasSpot(away: String, home: String): Boolean = toGoal in 1..99 && (team == away || team == home)
}

data class Situation(val team: String, val down: Int, val distance: Int, val toGoal: Int)

data class GameTime(val period: Int, val elapsed: Int) : Comparable<GameTime> {
    override fun compareTo(other: GameTime): Int = compareValuesBy(this, other, { it.period }, { it.elapsed })
}

/** The play whose text the screen is showing: which, and what it ran from. */
data class Landed(
    val sequence: Int,
    val situation: Situation,
    val period: String = "",
    /** Game clock at the snap, `"14:11"`. */
    val clock: String = "",
    val playType: String = "",
)

/** Every live game's provisional play, kept across polls. */
class PendingPlays {
    private class GameTrack(
        var snap: Snap,
        var changedAt: Double,
        var landed: Int? = null,
        var seenAt: GameTime? = null,
        var origin: Snap? = null,
        var originAt: GameTime? = null,
        var originKickoff: Boolean = false,
        var earlier: Snap? = null,
        var kickoffDue: Boolean = false,
        var unclaimed: Boolean = false,
        var text: String = "",
        var since: Double = 0.0,
    )

    private val games = LinkedHashMap<String, GameTrack>()

    /**
     * Take one poll's view of one game. [newest] is the play whose text the screen is now showing. Also
     * called between polls with the same [game] when only the play feed has been re-read.
     */
    fun observe(playsId: String, game: GameState, newest: Landed?, now: Double) {
        if (game.state != "in") {
            games.remove(playsId)
            return
        }
        val away = game.away
        val home = game.home
        val snap = Pending.snapOf(game)
        val seenAt = Pending.gameTime(game.period, game.clock)
        val state = games[playsId]
        if (state == null) {
            // A first look has nothing to compare against; it is the baseline.
            games[playsId] = GameTrack(snap, Double.NEGATIVE_INFINITY, landed = newest?.sequence, seenAt = seenAt)
            return
        }

        val fresh = newest != null && (state.landed == null || newest.sequence > state.landed!!)
        val moved = fresh && newest!!.playType !in Pending.NO_BALL_TYPES
        if (fresh && state.origin == null) {
            state.unclaimed = state.unclaimed || moved
        } else if (fresh) {
            val earlier = state.earlier
            if (earlier != null && newest!!.situation == earlier.situation) {
                state.earlier = null // the play BEFORE the awaited one landed; this one still waits
            } else {
                state.origin = null
                state.earlier = null
                state.text = ""
            }
        }
        if (newest != null) state.landed = newest.sequence

        if (snap == state.snap) return
        if (state.origin != null && snap == state.origin) {
            // Back where the awaited play started: it was undone — a flag, a review.
            state.text = ""
            state.snap = snap
            state.changedAt = now
            state.seenAt = seenAt
            return
        }
        if (now - state.changedAt >= Pending.REFINE_SECONDS) {
            state.earlier = state.origin
            val origin = state.snap
            state.origin = origin
            state.originAt = state.seenAt
            state.originKickoff = state.kickoffDue
            state.since = now
            if (moved || state.unclaimed || (newest != null && Pending.ranFrom(newest, origin, state.originAt))) {
                state.origin = null
            }
        }
        // Spent by any change, a refinement included.
        state.unclaimed = false
        if (snap.team != state.snap.team && (snap.team == away || snap.team == home)) state.kickoffDue = false
        if (Pending.scored(state.snap, snap, away, home) != null) state.kickoffDue = true
        state.text = state.origin?.let { Pending.describeChange(it, snap, away, home, state.originKickoff) } ?: ""
        state.snap = snap
        state.changedAt = now
        state.seenAt = seenAt
    }

    /** Drop every game not in [live] — finished, or gone from the feed. */
    fun forgetOthers(live: Set<String>) {
        games.keys.retainAll(live)
    }

    /** The provisional line for a game, or `""` when its text is in. */
    fun text(playsId: String): String = games[playsId]?.text ?: ""

    /** `{plays_id: provisional line}` for every game awaiting text. */
    fun texts(): Map<String, String> = games.filterValues { it.text.isNotEmpty() }.mapValues { it.value.text }

    /** The awaited snap as a row of the expanded play list, with the down and distance it ran from. */
    fun row(playsId: String, game: GameState): NflPlayRow? {
        val state = games[playsId] ?: return null
        val origin = state.origin
        if (state.text.isEmpty() || origin == null) return null
        val situation = if (origin.hasSpot(game.away, game.home)) {
            LeagueState.downAndDistance(origin.down, origin.distance, origin.toGoal)
        } else {
            ""
        }
        return NflPlayRow(
            playId = "",
            text = state.text,
            shortText = state.text,
            situation = situation,
            period = game.period,
            clock = "",
            provisional = true,
        )
    }

    /** Games whose play feed is worth re-reading before the next poll. */
    fun awaiting(now: Double): List<String> =
        games.filter { (_, s) -> s.text.isNotEmpty() && now - s.since < Pending.CHASE_FOR_SECONDS }.keys.toList()
}
