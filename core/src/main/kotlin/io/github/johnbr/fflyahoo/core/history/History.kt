package io.github.johnbr.fflyahoo.core.history

import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.scoring.Scoring
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A player's season, week by week: Yahoo's projection, what he scored, and whom he played. Port of the
 * integration's `history.py` — see its docstring for why three sources.
 *
 * * Yahoo's league seed for each week (`&week=N`): every rostered player's projection for that week.
 * * ESPN's fantasy API: every player's raw stat line per week, scored here with the league's own
 *   modifiers by the same [Scoring.score] the live totals use; and ESPN's projected line per week,
 *   scored the same way, for a week the player was on no roster here and so has no Yahoo projection.
 * * ESPN's NFL schedule: each club's opponent every week, and its bye. ESPN numbers the clubs as Yahoo
 *   does.
 *
 * Pure: payloads in, rows out. Fetching and caching are [io.github.johnbr.fflyahoo.core.client.SeasonHistory].
 */
object History {
    const val ESPN_READS = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons"

    /** The league seed as it stood, or will stand, in [week]. */
    fun seedWeekUrl(leagueId: String, week: Int): String = "${Relay.redzoneUrl(leagueId)}&week=$week"

    /** Every active NFL player ESPN knows: id, name, club and position. About 650 KB. */
    fun espnPlayersUrl(season: Int): String = "$ESPN_READS/$season/players?scoringPeriodId=0&view=players_wl"

    val ESPN_PLAYERS_FILTER: String = buildJsonObject {
        put("filterActive", buildJsonObject { put("value", true) })
    }.toString()

    /** One player's weekly stat lines; [espnStatsFilter] says which player. */
    fun espnStatsUrl(season: Int): String = "$ESPN_READS/$season/segments/0/leaguedefaults/3?view=kona_player_info"

    /** ESPN's `statSourceId`: what a player did, and what ESPN projected him to do. */
    const val ESPN_ACTUAL = 0
    const val ESPN_PROJECTED = 1

    /** The `X-Fantasy-Filter` header for one player's weekly lines, actual and projected. */
    fun espnStatsFilter(espnId: Long): String = buildJsonObject {
        put(
            "players",
            buildJsonObject {
                put("filterIds", valueOf(espnId))
                put("filterStatsForSourceIds", valueOf(ESPN_ACTUAL.toLong(), ESPN_PROJECTED.toLong()))
                put("filterStatsForSplitTypeIds", valueOf(1))
            },
        )
    }.toString()

    private fun valueOf(vararg n: Long) = buildJsonObject { put("value", buildJsonArray { for (v in n) add(JsonPrimitive(v)) }) }

    private fun valueOf(n: Int) = valueOf(n.toLong())

    /** Every club's games, week by week, and its bye. About 110 KB. */
    fun espnScheduleUrl(season: Int): String = "$ESPN_READS/$season?view=proTeamSchedules_wl"

    /** The NFL season a `weekInfo` belongs to: a January or February week is last year's. */
    fun seasonOf(weekInfo: JsonObject?): Int? {
        val start = (weekInfo?.get("start") as? JsonPrimitive)?.content ?: return null
        val date = try {
            LocalDate.parse(start)
        } catch (_: DateTimeParseException) {
            return null
        }
        return if (date.monthValue < 3) date.year - 1 else date.year
    }

    // --- ESPN's players, matched to Yahoo's ---------------------------------------------------------

    /** ESPN's `defaultPositionId` for the positions a fantasy roster holds. */
    val ESPN_POSITIONS: Map<String, Int> = mapOf("QB" to 1, "RB" to 2, "WR" to 3, "TE" to 4, "K" to 5, "DEF" to 16)
    const val ESPN_DEFENSE = 16

    data class EspnPlayer(val espnId: Long, val name: String, val teamId: String, val positionId: Int)

    fun parseEspnPlayers(payload: JsonElement?): List<EspnPlayer> =
        (payload as? JsonArray ?: return emptyList()).mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            EspnPlayer(
                espnId = id,
                name = (o["fullName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                teamId = ((o["proTeamId"] as? JsonPrimitive)?.longOrNull ?: 0L).toString(),
                positionId = (o["defaultPositionId"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }

    private val SUFFIX = Regex("""\b(?:jr|sr|ii|iii|iv|v)\b\.?""")

    /** `"Kenneth Walker III"` and `"Kenneth Walker"` alike: letters only, suffixes gone. */
    fun nameKey(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).filter { it.code < 128 }.lowercase()
        return SUFFIX.replace(ascii, "").filter { it in 'a'..'z' }
    }

    private fun surnameKey(name: String): String {
        val words = SUFFIX.replace(name.lowercase(), "").split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.isEmpty()) "" else nameKey(words.last())
    }

    /**
     * ESPN's entry for a Yahoo player, or null rather than a guess. A defence is its club. Anyone else is
     * matched on name, then position, then club — ESPN lists two Lamar Jacksons and two Justin Jeffersons
     * — with a same-club, same-position, same-surname fallback for a name written differently.
     */
    fun matchEspnPlayer(players: List<EspnPlayer>, name: String, teamId: String, position: String): EspnPlayer? {
        val positionId = ESPN_POSITIONS[position.uppercase()]
        if (positionId == ESPN_DEFENSE) {
            return players.firstOrNull { it.positionId == ESPN_DEFENSE && it.teamId == teamId }
        }
        val key = nameKey(name)
        var named = players.filter { nameKey(it.name) == key }
        for (narrow in listOf<(EspnPlayer) -> Boolean>(
            { positionId == null || it.positionId == positionId },
            { it.teamId == teamId },
        )) {
            if (named.size > 1) named = named.filter(narrow).ifEmpty { named }
        }
        if (named.size == 1) return named[0]
        if (named.isNotEmpty()) return null
        val surname = surnameKey(name)
        val alike = players.filter { it.teamId == teamId && it.positionId == positionId && surnameKey(it.name) == surname }
        return alike.singleOrNull()
    }

    /**
     * `{week: ESPN's raw stat line}` for the weeks this player has one; a missing actual week is a bye or a
     * blank. [ESPN_PROJECTED] reads the projected lines instead: every week, past ones too, a bye's empty.
     */
    fun parseEspnWeeks(
        payload: JsonElement?,
        espnId: Long,
        season: Int,
        source: Int = ESPN_ACTUAL,
    ): Map<Int, Map<String, Double>> {
        val weeks = LinkedHashMap<Int, Map<String, Double>>()
        val players = (payload as? JsonObject)?.get("players") as? JsonArray ?: return weeks
        for (entry in players) {
            val e = entry as? JsonObject ?: continue
            val player = e["player"] as? JsonObject ?: continue
            val id = (player["id"] as? JsonPrimitive)?.longOrNull ?: (e["id"] as? JsonPrimitive)?.longOrNull ?: 0L
            if (id != espnId) continue
            for (line in player["stats"] as? JsonArray ?: continue) {
                val l = line as? JsonObject ?: continue
                fun int(key: String) = (l[key] as? JsonPrimitive)?.intOrNull
                if (int("seasonId") != season || int("statSourceId") != source || int("statSplitTypeId") != 1) continue
                val week = int("scoringPeriodId") ?: 0
                if (week <= 0) continue
                val stats = LinkedHashMap<String, Double>()
                for ((k, v) in (l["stats"] as? JsonObject ?: JsonObject(emptyMap()))) {
                    stats[k] = (v as? JsonPrimitive)?.doubleOrNull ?: continue
                }
                weeks[week] = stats
            }
        }
        return weeks
    }

    // --- ESPN's stat line, in Yahoo's terms ---------------------------------------------------------

    /** Yahoo's stat name -> the ESPN stat ids that sum to it. In the integration's order, which the sums follow. */
    private val OFFENSE: List<Pair<String, List<Int>>> = listOf(
        "passingAttempts" to listOf(0), "completions" to listOf(1), "incompletePasses" to listOf(2),
        "passingYards" to listOf(3), "passingTDs" to listOf(4), "passingInterceptions" to listOf(20),
        "sacked" to listOf(64),
        "rushingAttempts" to listOf(23), "rushingYards" to listOf(24), "rushingTouchdowns" to listOf(25),
        "receptions" to listOf(53), "receptionYards" to listOf(42), "receptionTDs" to listOf(43),
        "receivingTargets" to listOf(58),
        "returnYards" to listOf(114, 115), "returnTDs" to listOf(101, 102),
        "twoPointConversions" to listOf(19, 26, 44),
        "fumbles" to listOf(68), "fumblesLost" to listOf(72), "offensiveFumbleReturnTDs" to listOf(63),
        "fieldGoalsMade40through49" to listOf(77), "fieldGoalsMissed40through49" to listOf(79),
        "fieldGoalsMade50plus" to listOf(74), "fieldGoalsMissed50plus" to listOf(76),
        "fieldGoalsMade60plus" to listOf(201), "fieldGoalsMissed60plus" to listOf(203),
        "fieldGoalsMade" to listOf(83), "fieldGoalsMissed" to listOf(85),
        "patMade" to listOf(86), "patMissed" to listOf(88),
    )

    private val DEFENSE: List<Pair<String, List<Int>>> = listOf(
        "pointsAllowed" to listOf(120), "yardsAllowed" to listOf(127),
        "sacks" to listOf(99), "interceptions" to listOf(95), "fumblesRecovered" to listOf(96),
        // Interception, fumble and blocked-kick returns; kick and punt returns are the special teams'.
        "defensiveTDs" to listOf(103, 104, 93),
        "intRetTds" to listOf(103), "fumbleRecTds" to listOf(104), "blkFgRetTds" to listOf(93),
        "safeties" to listOf(98), "blockedKicks" to listOf(97),
        "specialTeamsReturnYards" to listOf(114, 115), "kickReturnYards" to listOf(114), "puntReturnYards" to listOf(115),
        "specialTeamsReturnTDs" to listOf(101, 102), "kickRetTds" to listOf(101), "puntRetTds" to listOf(102),
        "extraPointReturned" to listOf(205),
    )

    /** ESPN counts field goals under 40 yards as one bucket; Yahoo has three. */
    private val UNDER_40_MADE = listOf("fieldGoalsMade0through19", "fieldGoalsMade20through29", "fieldGoalsMade30through39")
    private val UNDER_40_MISSED = listOf("fieldGoalsMissed0through19", "fieldGoalsMissed20through29", "fieldGoalsMissed30through39")
    private const val ESPN_UNDER_40_MADE = "80"
    private const val ESPN_UNDER_40_MISSED = "82"

    private fun sum(espn: Map<String, Double>, ids: List<Int>): Double {
        var total = 0.0 // Python's sum(): left to right from the integer 0
        for (id in ids) total += espn[id.toString()] ?: 0.0
        return total
    }

    /** ESPN's raw line for one week, as the stat names the league's modifiers are keyed by. */
    fun yahooStats(espn: Map<String, Double>, defense: Boolean): Map<String, Double> {
        if (defense) {
            val raw = LinkedHashMap<String, Double>()
            for ((name, ids) in DEFENSE) raw[name] = sum(espn, ids)
            raw["gamesPlayed"] = 1.0
            return Relay.defenseStats(raw)
        }
        val stats = LinkedHashMap<String, Double>()
        for ((name, ids) in OFFENSE) stats[name] = sum(espn, ids)
        stats["fieldGoalsMade50through59"] = stats.getValue("fieldGoalsMade50plus") - stats.getValue("fieldGoalsMade60plus")
        stats["fieldGoalsMissed50through59"] = stats.getValue("fieldGoalsMissed50plus") - stats.getValue("fieldGoalsMissed60plus")
        for (name in UNDER_40_MADE + UNDER_40_MISSED) stats[name] = 0.0
        stats["fieldGoalsMade30through39"] = espn[ESPN_UNDER_40_MADE] ?: 0.0
        stats["fieldGoalsMissed30through39"] = espn[ESPN_UNDER_40_MISSED] ?: 0.0
        return stats
    }

    private val RESOLVABLE: Set<String> = yahooStats(emptyMap(), false).keys + yahooStats(emptyMap(), true).keys

    /** Stats this league scores that ESPN's lines cannot say exactly. Empty for most leagues. */
    fun unresolvedStats(modifiers: Map<String, Double>): Set<String> {
        val scored = modifiers.filterValues { it != 0.0 }.keys
        val missing = scored.filterTo(LinkedHashSet()) { it !in RESOLVABLE }
        for (bucket in listOf(UNDER_40_MADE, UNDER_40_MISSED)) {
            if (bucket.map { modifiers[it] ?: 0.0 }.toSet().size > 1) missing += bucket.filter { it in scored }
        }
        return missing
    }

    /** `(points, exact)` for one ESPN line under this league's scoring. */
    fun weekPoints(espn: Map<String, Double>, defense: Boolean, modifiers: Map<String, Double>, unresolved: Set<String>): Pair<Double, Boolean> {
        val points = Scoring.score(yahooStats(espn, defense), modifiers)
        if (unresolved.isEmpty()) return points to true
        if ((UNDER_40_MADE + UNDER_40_MISSED).containsAll(unresolved)) {
            val kicks = (espn[ESPN_UNDER_40_MADE] ?: 0.0) + (espn[ESPN_UNDER_40_MISSED] ?: 0.0)
            return points to (kicks == 0.0)
        }
        return points to false
    }

    // --- The schedule -------------------------------------------------------------------------------

    data class Fixture(val week: Int, val opponentId: String, val home: Boolean, val kickoff: Double?)

    /** `{club id: {week: fixture}}`. A week a club has no fixture in is its bye. */
    fun parseEspnSchedule(payload: JsonElement?): Map<String, Map<Int, Fixture>> {
        val clubs = LinkedHashMap<String, Map<Int, Fixture>>()
        val teams = ((payload as? JsonObject)?.get("settings") as? JsonObject)?.get("proTeams") as? JsonArray ?: return clubs
        for (team in teams) {
            val t = team as? JsonObject ?: continue
            val club = ((t["id"] as? JsonPrimitive)?.longOrNull ?: 0L).toString()
            if (club == "0") continue // ESPN's "free agent" club
            val weeks = LinkedHashMap<Int, Fixture>()
            for ((week, games) in t["proGamesByScoringPeriod"] as? JsonObject ?: JsonObject(emptyMap())) {
                for (game in games as? JsonArray ?: continue) {
                    val g = game as? JsonObject ?: continue
                    fun id(key: String) = ((g[key] as? JsonPrimitive)?.longOrNull ?: 0L).toString()
                    val home = id("homeProTeamId") == club
                    val kickoff = (g["date"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it != 0.0 }
                    weeks[week.toInt()] = Fixture(week.toInt(), if (home) id("awayProTeamId") else id("homeProTeamId"), home, kickoff?.div(1000.0))
                }
            }
            clubs[club] = weeks
        }
        return clubs
    }

    /**
     * The clubs with no game in [week]: its byes. Empty for a week nobody plays in, and for one most clubs
     * sit out — a playoff week, where a club without a game is out, not resting. The NFL never rests more
     * than six at once.
     */
    fun byeClubs(schedule: Map<String, Map<Int, Fixture>>, week: Int): Set<String> {
        val idle = schedule.filterValues { week !in it }.keys
        return if (idle.size * 2 < schedule.size) idle else emptySet()
    }

    /** Every week anyone plays in — the regular season, bye weeks and all. */
    fun seasonWeeks(schedule: Map<String, Map<Int, Fixture>>): List<Int> = schedule.values.flatMap { it.keys }.toSortedSet().toList()

    // --- Yahoo's side ---------------------------------------------------------------------------------

    data class Identity(val playerId: String, val name: String, val teamId: String, val position: String) {
        val defense: Boolean get() = position == "DEF"
    }

    private fun league(seed: JsonObject, leagueId: String): JsonObject? =
        ((seed["service"] as? JsonObject)?.get("leagues") as? JsonObject)?.get(leagueId) as? JsonObject

    private fun str(el: JsonElement?): String = when (el) {
        null, JsonNull -> ""
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    /** The seed's own record of a rostered player — Yahoo's name, club id and position. */
    fun playerIdentity(seed: JsonObject, leagueId: String, playerId: String): Identity? {
        val meta = ((seed["service"] as? JsonObject)?.get("players") as? JsonObject)?.get(playerId) as? JsonObject ?: return null
        if (meta.isEmpty()) return null
        val defense = str(meta["positionType"]).uppercase() == "DT"
        val position = if (defense) "DEF" else str(meta["position"]).split(",")[0].uppercase()
        return Identity(playerId, str(meta["name"]), str(meta["teamId"]), position)
    }

    /** `{player id: projection}` for every player on a roster in the seed's week. */
    fun weekProjections(seed: JsonObject, leagueId: String): Map<String, Double> {
        val projections = LinkedHashMap<String, Double>()
        val teams = league(seed, leagueId)?.get("teams") as? JsonObject ?: return projections
        for (team in teams.values) {
            for (entry in (team as? JsonObject)?.get("players") as? JsonArray ?: continue) {
                val e = entry as? JsonObject ?: continue
                val value = e["projectedPoints"] as? JsonPrimitive ?: continue
                if (value.isString && value.content.isEmpty()) continue
                projections[str(e["id"])] = Py.floatOrNull(value.content) ?: continue
            }
        }
        return projections
    }

    fun seedWeekInfo(seed: JsonObject, leagueId: String): JsonObject? = league(seed, leagueId)?.get("weekInfo") as? JsonObject

    fun seedLeague(seed: JsonObject, leagueId: String): JsonObject? = league(seed, leagueId)

    // --- The season ---------------------------------------------------------------------------------

    @Serializable
    data class SeasonRow(
        val week: Int,
        /** `final` before this week, `live` for it, `upcoming` after it, `bye` whenever it falls. */
        val status: String,
        val opponent: String,
        val home: Boolean?,
        val kickoff: Double?,
        /**
         * Yahoo's; for a week he was on no roster in this league, which Yahoo does not project, ESPN's
         * projected line under the league's scoring; null when neither has one.
         */
        val projected: Double?,
        /** `yahoo` or `espn`, whichever [projected] is; null with it. */
        val projectedSource: String?,
        /** Null for a week to come, or a finished one ESPN has no player to match for. */
        val points: Double?,
        val exact: Boolean,
    )

    @Serializable
    data class SeasonTotals(
        val points: Double,
        val weeks: Int,
        val average: Double?,
        val projectedRemaining: Double,
        val exact: Boolean,
    )

    @Serializable
    data class PlayerSeason(
        val playerId: String,
        val name: String,
        val nflTeam: String,
        val position: String,
        val season: Int,
        val week: Int,
        /** False when ESPN has no player to match: the finished weeks' points are then unknown. */
        val matched: Boolean,
        val rows: List<SeasonRow>,
        val totals: SeasonTotals,
    )

    /** One row per week of the season, in order. See the integration's `season_rows`. */
    fun seasonRows(
        identity: Identity,
        weeks: List<Int>,
        currentWeek: Int,
        projections: Map<Int, Map<String, Double>>,
        actuals: Map<Int, Map<String, Double>>?,
        fixtures: Map<Int, Fixture>,
        modifiers: Map<String, Double>,
        livePoints: Double?,
        espnProjections: Map<Int, Map<String, Double>>? = null,
    ): List<SeasonRow> {
        val unresolved = unresolvedStats(modifiers)
        return weeks.map { week ->
            val fixture = fixtures[week]
                ?: return@map SeasonRow(week, "bye", "BYE", null, null, null, null, null, true)
            val opponent = Relay.teamAbbr(fixture.opponentId)
            val yahoo = projections[week]?.get(identity.playerId)
            val espnLine = espnProjections?.get(week)
            val (projected, source) = when {
                yahoo != null -> yahoo to "yahoo"
                // An empty line is ESPN projecting nothing, a player ruled out. Scored, a defence's would
                // read as a shutout.
                espnLine != null ->
                    (if (espnLine.isEmpty()) 0.0 else weekPoints(espnLine, identity.defense, modifiers, unresolved).first) to "espn"
                else -> null to null
            }
            val label = if (fixture.home) "vs $opponent" else "@ $opponent"
            when {
                week < currentWeek -> {
                    var points: Double? = null
                    var exact = true
                    if (actuals != null) {
                        val line = actuals[week]
                        if (line == null) {
                            points = 0.0
                        } else {
                            val (p, e) = weekPoints(line, identity.defense, modifiers, unresolved)
                            points = p
                            exact = e
                        }
                    }
                    SeasonRow(week, "final", label, fixture.home, fixture.kickoff, projected, source, points, exact)
                }
                week == currentWeek ->
                    SeasonRow(week, "live", label, fixture.home, fixture.kickoff, projected, source, livePoints, true)
                else -> SeasonRow(week, "upcoming", label, fixture.home, fixture.kickoff, projected, source, null, true)
            }
        }
    }

    /** Points so far, the weeks they came from, and what the rest of the season projects. */
    fun seasonTotals(rows: List<SeasonRow>): SeasonTotals {
        val played = rows.filter { it.status in setOf("final", "live") && it.points != null }
        val finals = played.filter { it.status == "final" }
        return SeasonTotals(
            points = Py.round(Py.sumOf(played) { it.points!! }, 2),
            weeks = finals.size,
            average = if (finals.isEmpty()) null else Py.round(Py.sumOf(finals) { it.points!! } / finals.size, 2),
            projectedRemaining = Py.round(Py.sumOf(rows.filter { it.status == "upcoming" }) { it.projected ?: 0.0 }, 2),
            exact = rows.all { it.exact },
        )
    }

    /** For tests and the app's file store: a JSON payload read leniently. */
    fun parse(body: String): JsonElement = Json.parseToJsonElement(body)
}
