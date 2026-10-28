package io.github.johnbr.fflyahoo.core.client

import io.github.johnbr.fflyahoo.core.history.History
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.seed.Seed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** `(url, headers) -> body`, for the ESPN requests that carry an `X-Fantasy-Filter`. */
fun interface HeaderFetcher {
    suspend fun fetch(url: String, headers: Map<String, String>): String
}

/** This week's and future weeks' projections move with injury news. */
const val PROJECTION_TTL_SECONDS = 6 * 3600.0

/** A player's ESPN lines, actual and projected in one request: corrections land in the days after a game. */
const val ESPN_TTL_SECONDS = 6 * 3600.0

/** ESPN's player list and the schedule. */
const val DIRECTORY_TTL_SECONDS = 24 * 3600.0

/** After a schedule fetch fails, how long the live poll leaves ESPN alone — a bye line is not worth asking every poll for. */
const val SCHEDULE_RETRY_SECONDS = 15 * 60.0

/** Seeds fetched at once on a cold start — a season of them is the first opening's whole cost. */
const val SEEDS_AT_ONCE = 4

/** The player is on no roster in the league this week. */
class PlayerNotRostered(playerId: String) : NoSuchElementException("player $playerId is on no roster this week")

/**
 * One league's player seasons, with everything they are built from cached. Port of the integration's
 * `history_client.SeasonHistory`: a finished week's projections are kept for good (and handed to [save]),
 * open weeks for [PROJECTION_TTL_SECONDS], ESPN's lists for a day. A source that fails leaves its part
 * of the rows empty, except the schedule, without which there are no weeks to show.
 */
class SeasonHistory(
    private val fetch: Fetcher,
    private val fetchWith: HeaderFetcher,
    val leagueId: String,
    private val save: (() -> Unit)? = null,
) {
    private class Kept<T>(val fetchedAt: Double, val value: T) {
        fun fresh(now: Double, ttl: Double) = now - fetchedAt < ttl
    }

    private var season: Int? = null
    private var final = LinkedHashMap<Int, Map<String, Double>>()
    private val open = LinkedHashMap<Int, Kept<Map<String, Double>>>()
    private var current: Kept<Pair<Int, JsonObject>>? = null
    private var schedule: Kept<Pair<Int, Map<String, Map<Int, History.Fixture>>>>? = null
    private var players: Kept<Pair<Int, List<History.EspnPlayer>>>? = null
    private val lines = HashMap<Long, Kept<EspnLines>>()
    private val lock = Mutex()
    private var scheduleRetryAt = 0.0

    // --- persistence --------------------------------------------------------------------------------

    fun load(stored: JsonObject?) {
        if (stored == null || (stored["league_id"] as? JsonPrimitive)?.content != leagueId) return
        season = (stored["season"] as? JsonPrimitive)?.intOrNull
        final = LinkedHashMap()
        for ((week, projections) in stored["projections"] as? JsonObject ?: JsonObject(emptyMap())) {
            final[week.toInt()] = (projections as? JsonObject ?: continue).mapNotNull { (id, v) ->
                (v as? JsonPrimitive)?.doubleOrNull?.let { id to it }
            }.toMap()
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("league_id", leagueId)
        season?.let { put("season", it) }
        put(
            "projections",
            buildJsonObject {
                for ((week, projections) in final) {
                    put(week.toString(), buildJsonObject { for ((id, v) in projections) put(id, v) })
                }
            },
        )
    }

    // --- the season ---------------------------------------------------------------------------------

    /** A player's season as rows. [livePoints] is this week's total, as the matchup shows it. */
    suspend fun player(playerId: String, currentWeek: Int, livePoints: Double?, now: Double): History.PlayerSeason {
        val built = lock.withLock {
            val seed = currentSeed(currentWeek, now)
            val identity = History.playerIdentity(seed, leagueId, playerId) ?: throw PlayerNotRostered(playerId)
            val year = History.seasonOf(History.seedWeekInfo(seed, leagueId))
                ?: throw IllegalStateException("the league seed carries no week dates")
            if (year != season) {
                season = year
                final = LinkedHashMap()
            }
            val modifiers = Seed.scoringModifiers(History.seedLeague(seed, leagueId) ?: JsonObject(emptyMap()))
            val clubs = scheduleFor(year, now)
            val weeks = History.seasonWeeks(clubs)
            val projections = projections(weeks, currentWeek, now)
            val espn = espnLines(identity, year, now)
            Built(identity, year, modifiers, clubs, weeks, projections, espn?.actual, espn?.projected)
        }
        val rows = History.seasonRows(
            built.identity, built.weeks, currentWeek, built.projections, built.actuals,
            built.clubs[built.identity.teamId] ?: emptyMap(), built.modifiers, livePoints, built.espnProjections,
        )
        return History.PlayerSeason(
            playerId = built.identity.playerId,
            name = built.identity.name,
            nflTeam = Relay.teamAbbr(built.identity.teamId),
            position = built.identity.position,
            season = built.season,
            week = currentWeek,
            matched = built.actuals != null,
            rows = rows,
            totals = History.seasonTotals(rows),
        )
    }

    private class Built(
        val identity: History.Identity,
        val season: Int,
        val modifiers: Map<String, Double>,
        val clubs: Map<String, Map<Int, History.Fixture>>,
        val weeks: List<Int>,
        val projections: Map<Int, Map<String, Double>>,
        val actuals: Map<Int, Map<String, Double>>?,
        val espnProjections: Map<Int, Map<String, Double>>?,
    )

    /** One player's ESPN lines by week: what he did, and what ESPN projected. */
    private class EspnLines(val actual: Map<Int, Map<String, Double>>, val projected: Map<Int, Map<String, Double>>)

    private suspend fun currentSeed(week: Int, now: Double): JsonObject {
        val kept = current
        if (kept != null && kept.value.first == week && kept.fresh(now, PROJECTION_TTL_SECONDS)) return kept.value.second
        val seed = Seed.parse(get(fetch, History.seedWeekUrl(leagueId, week)))
        current = Kept(now, week to seed)
        open[week] = Kept(now, History.weekProjections(seed, leagueId))
        return seed
    }

    /**
     * The clubs on a bye in the seed's week, by Yahoo club id, from ESPN's schedule. For the live poll, so it
     * never throws and never waits on a season being opened: no lock, and a failed fetch keeps the last
     * schedule (or none) for [SCHEDULE_RETRY_SECONDS] rather than asking ESPN again every poll. Empty when
     * the schedule cannot be had — those players then read as before, with no game line.
     */
    suspend fun byes(seed: JsonObject, now: Double): Set<String> {
        val weekInfo = History.seedWeekInfo(seed, leagueId)
        val year = History.seasonOf(weekInfo) ?: return emptySet()
        val week = (weekInfo?.get("week") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (week <= 0) return emptySet()
        var kept = schedule
        var usable = kept != null && kept.value.first == year
        if (!(usable && kept!!.fresh(now, DIRECTORY_TTL_SECONDS)) && now >= scheduleRetryAt) {
            try {
                scheduleFor(year, now)
                kept = schedule
                usable = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { // a missing bye line is no reason to fail a poll
                scheduleRetryAt = now + SCHEDULE_RETRY_SECONDS
            }
        }
        return if (usable) History.byeClubs(kept!!.value.second, week) else emptySet()
    }

    private suspend fun scheduleFor(year: Int, now: Double): Map<String, Map<Int, History.Fixture>> {
        val kept = schedule
        if (kept != null && kept.value.first == year && kept.fresh(now, DIRECTORY_TTL_SECONDS)) return kept.value.second
        val parsed = History.parseEspnSchedule(History.parse(get(fetch, History.espnScheduleUrl(year))))
        schedule = Kept(now, year to parsed)
        return parsed
    }

    /** Every week's projections, fetching only what is missing or stale. */
    private suspend fun projections(weeks: List<Int>, currentWeek: Int, now: Double): Map<Int, Map<String, Double>> {
        val wanted = weeks.filter { it !in final && open[it]?.fresh(now, PROJECTION_TTL_SECONDS) != true }
        val gate = Semaphore(SEEDS_AT_ONCE)
        val fetched = coroutineScope {
            wanted.map { week ->
                async {
                    gate.withPermit {
                        // One missing week is a blank column, not a failure.
                        week to attempt { History.weekProjections(Seed.parse(get(fetch, History.seedWeekUrl(leagueId, week))), leagueId) }.getOrNull()
                    }
                }
            }.awaitAll()
        }
        var finished = false
        for ((week, projections) in fetched) {
            if (projections == null) continue
            if (week < currentWeek) {
                final[week] = projections
                open.remove(week)
                finished = true
            } else {
                open[week] = Kept(now, projections)
            }
        }
        if (finished) save?.invoke()
        return LinkedHashMap<Int, Map<String, Double>>().apply {
            for ((week, kept) in open) put(week, kept.value)
            putAll(final)
        }
    }

    /** ESPN's weekly lines for the player, or null when ESPN cannot be read or has no player to match. */
    private suspend fun espnLines(identity: History.Identity, year: Int, now: Double): EspnLines? =
        attempt {
            var kept = players
            if (kept == null || kept.value.first != year || !kept.fresh(now, DIRECTORY_TTL_SECONDS)) {
                val body = fetchWith.fetch(History.espnPlayersUrl(year), mapOf("X-Fantasy-Filter" to History.ESPN_PLAYERS_FILTER))
                kept = Kept(now, year to History.parseEspnPlayers(History.parse(body)))
                players = kept
            }
            val match = History.matchEspnPlayer(kept.value.second, identity.name, identity.teamId, identity.position)
                ?: return@attempt null
            var found = lines[match.espnId]
            if (found == null || !found.fresh(now, ESPN_TTL_SECONDS)) {
                val body = fetchWith.fetch(History.espnStatsUrl(year), mapOf("X-Fantasy-Filter" to History.espnStatsFilter(match.espnId)))
                val payload = History.parse(body)
                found = Kept(
                    now,
                    EspnLines(
                        History.parseEspnWeeks(payload, match.espnId, year, History.ESPN_ACTUAL),
                        History.parseEspnWeeks(payload, match.espnId, year, History.ESPN_PROJECTED),
                    ),
                )
                lines[match.espnId] = found
            }
            found.value
        }.getOrNull()
}
