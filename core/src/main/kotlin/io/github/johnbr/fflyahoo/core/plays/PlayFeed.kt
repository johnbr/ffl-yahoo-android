package io.github.johnbr.fflyahoo.core.plays

import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.scoring.Scoring
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bounded, de-duplicated, per-week scoring history. Port of `plays.PlayFeed`.
 *
 * Persisted as JSON so a restart in the middle of a Sunday does not lose the afternoon.
 */
class PlayFeed(private val maxlen: Int = Plays.DEFAULT_HISTORY) {
    private val events = ArrayDeque<ScoringEvent>()
    private val seen = HashSet<String>()

    var week: Int? = null
        private set

    /** Counts every change to the history, so a caller knows when to persist it. */
    var version: Int = 0
        private set

    val size: Int get() = events.size

    /** Oldest first. */
    fun all(): List<ScoringEvent> = events.toList()

    private fun indexOf(eventId: String): Int = events.indexOfFirst { it.eventId == eventId }

    /**
     * Append new events, dropping ones already recorded. Returns what stuck. A new week clears the
     * feed. A later piece of a play already on the feed is folded into that row, and the row as it now
     * reads is returned in its place — the running total under the id the caller already knows.
     */
    fun add(newEvents: List<ScoringEvent>): List<ScoringEvent> {
        val added = ArrayList<ScoringEvent>()
        for (event in newEvents) {
            if (week != null && event.week != week) clear()
            week = event.week

            if (event.eventId in seen) continue
            val host = rowShowing(event)
            if (host != null) {
                added += absorb(host, event)
                continue
            }
            if (events.size == maxlen && events.isNotEmpty()) {
                // Eviction must also release the id, or a long game eventually refuses to record anything.
                seen.remove(events.removeFirst().eventId)
            }
            events.addLast(event)
            seen += event.eventId
            version++
            added += event
        }
        return added
    }

    /**
     * Replace a stored event's matched play, keeping its place in history. A revision that lands the
     * event on a play another of the player's rows already shows makes the two one row: the older keeps
     * its place and its id, the younger is folded in.
     */
    fun revise(eventId: String, play: MatchedPlay?): ScoringEvent? {
        val index = indexOf(eventId)
        if (index < 0) return null
        val updated = events[index].copy(play = play)
        events[index] = updated
        version++
        val other = rowShowing(updated) ?: return updated
        val (host, piece) = if (indexOf(other.eventId) < index) other to updated else updated to other
        val merged = absorb(host, piece)
        events.removeAt(indexOf(piece.eventId))
        return merged
    }

    /**
     * Fold an event that never got a play of its own into its player's last play. The caller decides
     * WHEN this is safe; this decides WHETHER: the player's nearest older row must be showing a play and
     * be within [Plays.SPLIT_TICK_SECONDS] of the piece.
     */
    fun fold(eventId: String): ScoringEvent? {
        val index = indexOf(eventId)
        if (index < 0) return null
        val piece = events[index]
        if (piece.correction || piece.play != null) return null
        for (i in index - 1 downTo 0) {
            val host = events[i]
            if (host.playerKey != piece.playerKey || host.correction) continue
            if (host.play == null || piece.timestamp - host.timestamp > Plays.SPLIT_TICK_SECONDS) return null
            val merged = absorb(host, piece)
            events.removeAt(index)
            return merged
        }
        return null
    }

    /** Another of the player's rows already matched to this event's play. */
    private fun rowShowing(event: ScoringEvent): ScoringEvent? {
        val playId = event.play?.playId
        if (event.correction || playId.isNullOrEmpty()) return null
        return events.lastOrNull { other ->
            other.eventId != event.eventId &&
                other.playerKey == event.playerKey &&
                !other.correction &&
                other.play?.playId == playId
        }
    }

    /** Fold [piece] into [host] in place; the piece's id counts as seen. */
    private fun absorb(host: ScoringEvent, piece: ScoringEvent): ScoringEvent {
        val merged = host.copy(
            delta = Py.round(host.delta + piece.delta, 2),
            newPoints = if (piece.timestamp >= host.timestamp) piece.newPoints else host.newPoints,
            statDelta = Scoring.mergeStatDeltas(host.statDelta, piece.statDelta),
            absorbed = host.absorbed + piece.eventId + piece.absorbed,
        )
        events[indexOf(host.eventId)] = merged
        seen += piece.eventId
        version++
        return merged
    }

    /**
     * Make [newWeek] the feed's week, once per poll. Returns whether an older week was cleared. It has
     * to move `week` as well as clear, or every poll of a new week's first game resets the baseline.
     */
    fun startWeek(newWeek: Int?): Boolean {
        if (newWeek == null || newWeek == week) return false
        val rolled = week != null
        if (rolled) clear()
        week = newWeek
        return rolled
    }

    fun clear() {
        events.clear()
        seen.clear()
        version++
    }

    /**
     * Most recent events first. [startersOnly] drops bench and IR players; [nflTeams] restricts to
     * players on those clubs (`null` means no restriction — what an unavailable games feed falls back to).
     */
    fun recent(
        limit: Int = 10,
        matchupId: String? = null,
        teamKey: String? = null,
        includeCorrections: Boolean = false,
        startersOnly: Boolean = false,
        nflTeams: Set<String>? = null,
    ): List<ScoringEvent> {
        val out = ArrayList<ScoringEvent>()
        for (event in events.asReversed()) {
            if (!includeCorrections && event.correction) continue
            if (startersOnly && !event.starter) continue
            if (nflTeams != null && (event.nflTeam ?: "") !in nflTeams) continue
            if (matchupId != null && event.matchupId != matchupId) continue
            if (teamKey != null && event.teamKey != teamKey) continue
            out += event
            if (out.size >= limit) break
        }
        return out
    }

    fun lastPlay(
        matchupId: String? = null,
        teamKey: String? = null,
        includeCorrections: Boolean = false,
        startersOnly: Boolean = false,
        nflTeams: Set<String>? = null,
    ): ScoringEvent? = recent(1, matchupId, teamKey, includeCorrections, startersOnly, nflTeams).firstOrNull()

    // -- persistence --------------------------------------------------------------------------------

    @Serializable
    private data class Stored(val week: Int? = null, val events: List<ScoringEvent> = emptyList())

    fun toJson(): String = JSON.encodeToString(Stored.serializer(), Stored(week, events.toList()))

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Restore from [toJson] output; unreadable input yields an empty feed. */
        fun fromJson(payload: String?, maxlen: Int = Plays.DEFAULT_HISTORY): PlayFeed {
            val feed = PlayFeed(maxlen)
            if (payload.isNullOrEmpty()) return feed
            val stored = try {
                JSON.decodeFromString(Stored.serializer(), payload)
            } catch (_: Exception) {
                return feed
            }
            feed.week = stored.week
            for (event in stored.events) {
                if (feed.events.size == maxlen) feed.events.removeFirst()
                feed.events.addLast(event)
                feed.seen += event.eventId
                feed.seen += event.absorbed
            }
            return feed
        }
    }
}
