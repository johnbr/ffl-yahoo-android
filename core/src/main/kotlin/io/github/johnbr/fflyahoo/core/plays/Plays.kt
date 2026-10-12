package io.github.johnbr.fflyahoo.core.plays

import io.github.johnbr.fflyahoo.core.BENCH_SLOTS
import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.relay.RelayPlay
import io.github.johnbr.fflyahoo.core.scoring.Scoring
import kotlinx.serialization.Serializable

/**
 * Scoring-play engine — turn consecutive polls into scoring events. Port of `plays.py`.
 *
 * Yahoo publishes point totals, never events, so the feed is synthesized: diff every rostered player's
 * points against the previous poll and emit an event for each change. Exact to the league's scoring,
 * and every point counts, not just touchdowns. Limits: Yahoo trails the play by 30-60 s; two scores
 * inside one poll are one event; corrections look like plays (flagged, never presented as scores); and
 * one play lands in pieces a poll apart, which [PlayFeed] folds back into one row.
 */
object Plays {
    /** Ignore changes smaller than this — below anything a scoring system awards. */
    const val MIN_DELTA = 0.05

    /** Per-week history depth. */
    const val DEFAULT_HISTORY = 200

    /** How long after an event the text of its play can still be on the way. */
    const val PLAY_TEXT_LAG_SECONDS = 60.0

    /** The longest a play's stats have been seen to keep landing after the first piece. */
    const val SPLIT_TICK_SECONDS = 180.0

    /** Deterministic id for one transition — no timestamp, so re-ingesting a pair is idempotent. */
    fun eventId(week: Int, playerKey: String, old: Double, new: Double): String =
        "w$week:$playerKey:${Py.fixed(old)}->${Py.fixed(new)}"

    /**
     * Whether a point DROP is Yahoo revising a stat, or the player doing something bad. A real play
     * ADDS something (an interception, even a rush for a loss adds an attempt); a correction only walks
     * numbers back. With no stat line at all a drop stays a correction.
     */
    fun isCorrection(delta: Double, before: Map<String, Double>, after: Map<String, Double>): Boolean {
        if (delta >= 0) return false
        return (after.keys + before.keys).none { (after[it] ?: 0.0) > (before[it] ?: 0.0) }
    }

    /**
     * One event per player whose points changed between two polls. Nothing on the first poll, across a
     * week rollover, or for a player with no previous reading.
     */
    fun diffSnapshots(previous: LeagueSnapshot?, current: LeagueSnapshot, minDelta: Double = MIN_DELTA): List<ScoringEvent> {
        if (previous == null || previous.week != current.week) return emptyList()
        val events = ArrayList<ScoringEvent>()
        for ((playerKey, now) in current.players) {
            val before = previous.players[playerKey] ?: continue
            val delta = Py.round(now.points - before.points, 2)
            if (Math.abs(delta) < minDelta) continue
            events += ScoringEvent(
                eventId = eventId(current.week, playerKey, before.points, now.points),
                week = current.week,
                timestamp = current.takenAt,
                playerKey = playerKey,
                playerName = now.name,
                teamKey = now.teamKey,
                matchupId = now.matchupId,
                nflTeam = now.nflTeam,
                position = now.position,
                delta = delta,
                oldPoints = Py.round(before.points, 2),
                newPoints = Py.round(now.points, 2),
                starter = now.starter,
                correction = isCorrection(delta, before.stats, now.stats),
                statDelta = statDelta(before.stats, now.stats),
            )
        }
        // Biggest scores first within a poll; corrections sort to the end.
        return events.sortedWith(compareBy<ScoringEvent>({ it.correction }, { -Math.abs(it.delta) }))
    }

    private fun statDelta(before: Map<String, Double>, after: Map<String, Double>): String =
        if (before.isEmpty() && after.isEmpty()) "" else Scoring.describeDelta(before, after)

    /** `Puka Nacua` -> `P. Nacua`. A first name that is already initials (`D.J. Reed`) is kept whole. */
    fun abbreviateName(name: String?): String {
        val parts = (name ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) return name ?: ""
        val first = if ("." in parts[0]) parts[0] else "${parts[0].substring(0, parts[0].offsetByCodePoints(0, 1))}."
        return "$first ${parts.drop(1).joinToString(" ")}"
    }

    /**
     * `{id: "J. Goff"}`, except where that would name two different players (Bijan and Brian Robinson
     * both shorten to `B. Robinson`) — those keep their full names. [scope] is the ids about to be
     * rendered, normally one game's; `null` scopes it to the whole mapping. Unknown ids are left out.
     */
    fun abbreviateNames(names: Map<String, String>, scope: Iterable<String>? = null): Map<String, String> {
        val ids = scope?.filter { it in names } ?: names.keys.toList()
        val brief = LinkedHashMap<String, String>()
        for (pid in ids) brief[pid] = abbreviateName(names.getValue(pid))
        // Counted over distinct NAMES, not ids: the same player under two ids is one person.
        val sharing = HashMap<String, MutableSet<String>>()
        for ((pid, short) in brief) sharing.getOrPut(short) { HashSet() } += names.getValue(pid)
        return brief.mapValues { (pid, short) -> if (sharing.getValue(short).size > 1) names.getValue(pid) else short }
    }

    private fun playerIdOf(event: ScoringEvent): String = event.playerKey.substringAfterLast(".p.")

    /**
     * The play in [plays] (oldest first) that this event's points most likely came from, matched by
     * Yahoo player id. New events take the newest play above their floor; [pin] re-reads an event that
     * already matched for better wording of the SAME play. Null means "keep what you have".
     */
    fun matchRelayPlay(event: ScoringEvent, plays: List<RelayPlay>, names: Map<String, String>, pin: String? = null): MatchedPlay? {
        val playerId = playerIdOf(event)
        if (playerId.isEmpty()) return null
        val floor = event.playFloor
        for (play in plays.asReversed()) {
            if (pin != null) {
                if (play.playId != pin) continue
            } else if (floor != null && play.sequence <= floor) {
                return null // nothing further back can be it either
            } else if (playerId !in play.playerIds) {
                continue
            }
            val text = Relay.humanizePlay(play.text, names)
            if (text.isEmpty()) return null
            return MatchedPlay(text = text, period = Py.intOrNull(play.period), clock = play.clock, playId = play.playId)
        }
        return null
    }

    /** Whether the feed holds a play above the event's floor that names its player. Unknown floor: yes. */
    fun playAboveFloor(event: ScoringEvent, plays: List<RelayPlay>): Boolean {
        val floor = event.playFloor ?: return true
        val playerId = playerIdOf(event)
        return plays.any { it.sequence > floor && playerId in it.playerIds }
    }

    /** One-line text for an event: the real play, else the stat delta, else the point delta. */
    fun describe(event: ScoringEvent): String {
        val who = abbreviateName(event.playerName)
        if (event.correction) return "$who ${Py.signedFixed(event.delta)} (stat correction)"
        event.play?.text?.takeIf { it.isNotEmpty() }?.let { return it }
        if (event.statDelta.isNotEmpty()) return "$who ${event.statDelta}"
        return "$who ${Py.signedFixed(event.delta)}"
    }

    /** `("T. Etienne Jr.", "1 rec, 1 yd")` — who, and what they did. */
    fun shortParts(event: ScoringEvent): Pair<String, String> {
        val who = abbreviateName(event.playerName)
        if (event.correction) return who to "${Py.signedFixed(event.delta)} (stat correction)"
        if (event.statDelta.isNotEmpty()) return who to Scoring.shortenStatDelta(event.statDelta)
        return who to Py.signedFixed(event.delta)
    }

    /** `T. Etienne Jr. 1 rec, 1 yd` — the fantasy-side view of an event. */
    fun shortDescribe(event: ScoringEvent): String = shortParts(event).let { "${it.first} ${it.second}" }
}

/** One rostered player at one poll. */
data class PlayerSnapshot(
    val playerKey: String,
    val name: String,
    val points: Double,
    /** Fantasy team that rosters the player. */
    val teamKey: String,
    val matchupId: String? = null,
    val nflTeam: String? = null,
    val position: String? = null,
    /** Lineup slot this week — `BN` for bench. */
    val selectedPosition: String? = null,
    val stats: Map<String, Double> = emptyMap(),
) {
    val starter: Boolean get() = (selectedPosition ?: "").uppercase() !in BENCH_SLOTS
}

/** Every rostered player in the league at one poll. */
data class LeagueSnapshot(
    val week: Int,
    /** Epoch seconds, supplied by the caller — this module has no clock. */
    val takenAt: Double,
    val players: Map<String, PlayerSnapshot> = emptyMap(),
)

/** A relay play attributed to the player behind an event. */
@Serializable
data class MatchedPlay(
    val text: String,
    val period: Int? = null,
    val clock: String? = null,
    val playId: String? = null,
)

/** A change in one player's fantasy points between two polls. */
@Serializable
data class ScoringEvent(
    val eventId: String,
    val week: Int,
    val timestamp: Double,
    val playerKey: String,
    val playerName: String,
    val teamKey: String,
    val matchupId: String?,
    val nflTeam: String?,
    val position: String?,
    val delta: Double,
    val oldPoints: Double,
    val newPoints: Double,
    val starter: Boolean,
    val correction: Boolean,
    val play: MatchedPlay? = null,
    /** What changed, in Yahoo's phrasing — `1 Comp, 13 Pass Yds`. */
    val statDelta: String = "",
    /**
     * The newest play already in the game's feed when this event was raised. Only a play above it can
     * be this event's description. `null` when the feed's state was unknown.
     */
    val playFloor: Int? = null,
    /** Ids of the later pieces of this play folded into this event; treated as seen. */
    val absorbed: List<String> = emptyList(),
) {
    val enriched: Boolean get() = play != null
}
