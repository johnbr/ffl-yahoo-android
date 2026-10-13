package io.github.johnbr.fflyahoo.core.present

import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.state.NflGameRow
import io.github.johnbr.fflyahoo.core.state.ScoreBanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the screens format and group what the engine publishes. Port of the client-side logic in
 * `yahoo-fantasy-football-cards.js` — kept here, in plain Kotlin, so it is tested on the JVM.
 */
object Present {
    /** Points as shown on Yahoo: two decimals, an em dash when genuinely absent. */
    fun points(value: Double?): String = if (value == null || !value.isFinite()) "—" else Py.fixed(value)

    /** `+6.40` / `-0.30`; empty when absent. */
    fun delta(value: Double?): String {
        if (value == null || !value.isFinite()) return ""
        return (if (value > 0) "+" else "") + Py.fixed(value)
    }

    /** `59%`. */
    fun percent(value: Double?): String = if (value == null || !value.isFinite()) "" else "${(value * 100).roundToInt()}%"

    enum class Trend { UP, DOWN, FLAT }

    /** Which way a live projection has moved against the pre-game one; null when either is absent. */
    fun trend(live: Double?, projected: Double?): Trend? {
        if (live == null || projected == null || !live.isFinite() || !projected.isFinite()) return null
        if (abs(live - projected) < 0.005) return Trend.FLAT
        return if (live > projected) Trend.UP else Trend.DOWN
    }

    /** True when a live projection is worth printing beside the original. */
    fun hasLive(live: Double?, projected: Double?): Boolean = trend(live, projected).let { it == Trend.UP || it == Trend.DOWN }

    /** The viewer's calendar day for an epoch, or null for a missing or nonsense time. */
    fun localDay(epoch: Long?, zone: ZoneId): LocalDate? =
        if (epoch == null || epoch <= 0) null else Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()

    /**
     * `1:25` for a kickoff today, `Sun 1:25` for one on another day. AM/PM is dropped except from 11 PM
     * to 7 AM, where it is the only thing separating a London morning from something that never
     * happens. In a 24-hour locale there is no marker to drop.
     */
    fun kickoff(epoch: Long?, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault(), use24Hour: Boolean = false): String {
        if (epoch == null || epoch <= 0) return ""
        val whenAt = Instant.ofEpochSecond(epoch).atZone(zone)
        val time = clock(whenAt, locale, use24Hour)
        return if (whenAt.toLocalDate() == now.atZone(zone).toLocalDate()) {
            time
        } else {
            "${whenAt.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} $time"
        }
    }

    fun clock(at: ZonedDateTime, locale: Locale = Locale.getDefault(), use24Hour: Boolean = false): String {
        if (use24Hour) return at.format(DateTimeFormatter.ofPattern("H:mm", locale))
        val ambiguous = at.hour >= 23 || at.hour < 7
        val pattern = if (ambiguous) "h:mm a" else "h:mm"
        return at.format(DateTimeFormatter.ofPattern(pattern, locale)).replace(' ', ' ').trim()
    }

    /** The NFL slate, grouped the way the games screen shows it. */
    data class Split(
        val live: List<NflGameRow>,
        val soon: List<NflGameRow>,
        val later: List<NflGameRow>,
        val done: List<NflGameRow>,
    )

    /**
     * While any game is RUNNING only the live (and delayed) games show and every scheduled game folds
     * under "later this week". Otherwise today's scheduled games show (and any with no kickoff time),
     * the rest fold. Finished games always fold under "N final". Feed order is kept within each group.
     */
    fun split(rows: List<NflGameRow>, now: Instant, zone: ZoneId): Split {
        val live = ArrayList<NflGameRow>()
        val pre = ArrayList<NflGameRow>()
        val done = ArrayList<NflGameRow>()
        for (game in rows) {
            when (game.state) {
                "post" -> done += game
                "in", "delayed" -> live += game
                else -> pre += game
            }
        }
        if (live.any { it.state == "in" }) return Split(live, emptyList(), pre, done)
        val today = now.atZone(zone).toLocalDate()
        val soon = pre.filter { localDay(it.startTime, zone).let { day -> day == null || day == today } }
        return Split(live, soon, pre - soon.toSet(), done)
    }

    /** The games in progress (or delayed mid-game) that involve one of [clubs], feed order. */
    fun liveGamesFor(rows: List<NflGameRow>, clubs: Set<String>): List<NflGameRow> {
        val wanted = clubs.mapTo(HashSet()) { it.uppercase() }
        return rows.filter { game ->
            game.state in setOf("in", "delayed") && (game.away.abbr.uppercase() in wanted || game.home.abbr.uppercase() in wanted)
        }
    }

    /** A game's scoring banner, if one is still up at [now] (epoch seconds). */
    fun banner(game: NflGameRow, now: Double): ScoreBanner? = game.scoreBanner?.takeIf { it.kind.isNotEmpty() && it.until > now }

    /** The field bar's fill, 0-100, or null when there is no real spot. Red inside the 20. */
    fun fieldFill(game: NflGameRow): Int? {
        if (game.state != "in") return null
        val toGoal = game.yardsToGoal ?: return null
        if (toGoal <= 0 || toGoal >= 100) return null
        return 100 - toGoal
    }

    enum class Slot { QB, RB, WR, TE, K, DEF, BENCH, FLEX }

    /** Slot -> colour family. Anything unrecognised is a flex spot. */
    fun slot(slot: String?): Slot = when ((slot ?: "").uppercase()) {
        "QB" -> Slot.QB
        "RB" -> Slot.RB
        "WR" -> Slot.WR
        "TE" -> Slot.TE
        "K" -> Slot.K
        "DEF", "D/ST" -> Slot.DEF
        "BN", "IR", "IR+", "NA" -> Slot.BENCH
        else -> Slot.FLEX
    }

    /** `Q3 6:24` for a play-list row. */
    fun playWhen(period: String, clock: String): String =
        listOf(if (period.isNotEmpty()) "Q$period" else "", clock).filter { it.isNotEmpty() }.joinToString(" ")

    /** `2nd & 7, DAL 19`. */
    fun situation(game: NflGameRow): String = listOf(game.situation, game.ballOn).filter { it.isNotEmpty() }.joinToString(", ")
}
