package io.github.johnbr.fflyahoo.core.seed

import io.github.johnbr.fflyahoo.core.LeagueData
import io.github.johnbr.fflyahoo.core.Matchup
import io.github.johnbr.fflyahoo.core.Player
import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.Team
import io.github.johnbr.fflyahoo.core.plays.LeagueSnapshot
import io.github.johnbr.fflyahoo.core.plays.PlayerSnapshot
import io.github.johnbr.fflyahoo.core.relay.GameState
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.relay.Stats
import io.github.johnbr.fflyahoo.core.scoring.Scoring
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The league seed (`pub-api.fantasysports.yahoo.com/fantasy/v3/redzone/nfl?league_id=…`) folded
 * together with the live relay feeds into [LeagueData]. Port of the assembly half of
 * `yahoo_redzone.py`, plus `extract_league_id` from `yahoo_web.py`.
 */
object Seed {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): JsonObject =
        json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("seed is not a JSON object")

    /**
     * A team defence is **not** identifiable by `primaryPosition` (Yahoo sends null there). `positionType`
     * is the reliable marker: `DT` is team defence.
     */
    const val DEFENSE_POSITION_TYPE = "DT"

    /** The league's own points-per-stat table, keyed by relay stat name. */
    fun scoringModifiers(league: JsonObject): Map<String, Double> {
        val modifiers = LinkedHashMap<String, Double>()
        for (stat in league.list("stats")) {
            if (stat !is JsonObject || !truthy(stat["isScoring"])) continue
            val id = stat["id"]?.let { intOf(it) } ?: continue
            val name = Stats.NFL_STAT_NAMES[id] ?: continue
            modifiers[name] = if (truthy(stat["modifier"])) floatOf(stat["modifier"]!!) else 0.0
        }
        return modifiers
    }

    /** Fold one poll's three payloads into the shape the screens consume. */
    fun leagueFromPayloads(
        seed: JsonObject,
        statsText: String,
        gamesText: String,
        leagueId: String,
        now: Double,
    ): LeagueData {
        val league = league(service(seed), leagueId)
        val service = service(seed)

        val week = (league.obj("weekInfo")?.get("week")).let { if (truthy(it)) intOf(it!!) ?: throw bad("week") else 0 }
        val modifiers = scoringModifiers(league)
        val statsByPlayer = Relay.parseStats(statsText)
        val defenseByTeam = Relay.parseDefense(statsText)
        val games = Relay.parseGames(gamesText)
        // Before the rosters are built: a player's state, note and live projection all come from their
        // game's, and a forgotten game says "not yet".
        Relay.settleForgottenGames(games, now)
        val teams = league.obj("teams") ?: JsonObject(emptyMap())

        val matchups = ArrayList<Matchup>()
        val standings = ArrayList<Pair<Team, Team>>()
        for (group in league.list("matchupGroups")) {
            if (group !is JsonObject) continue
            for (pair in group.list("matchups")) {
                if (pair !is JsonArray || pair.size != 2) continue
                val sides = ArrayList<Team>()
                val players = ArrayList<Player>()
                pair.forEachIndexed { side, teamId ->
                    val team = teams[pyStr(teamId)] as? JsonObject ?: JsonObject(emptyMap())
                    val roster = roster(team, service, statsByPlayer, defenseByTeam, modifiers, games, side)
                    players += roster
                    sides += team(team, roster)
                }
                standings += sides[0] to sides[1]
                matchups += Matchup(week, sides[0] to sides[1], players)
            }
        }

        return LeagueData(
            week = week,
            matchups = matchups,
            standings = standings,
            fetchedAt = now,
            partial = matchups.isEmpty(),
            activeGames = Relay.activeGameCount(games),
            liveTick = Relay.liveSignature(games),
            // null when the games feed itself is missing — NOT the same as "no game is live".
            liveClubs = if (games.isNotEmpty()) Relay.liveClubs(games) else null,
            playsFeeds = Relay.playsFeeds(games),
            nflGames = Relay.gamesInOrder(games),
        )
    }

    private fun isDefense(entry: JsonObject, meta: JsonObject): Boolean =
        DEFENSE_POSITION_TYPE == strOr(entry["positionType"]).uppercase() ||
            DEFENSE_POSITION_TYPE == strOr(meta["positionType"]).uppercase()

    private fun player(
        entry: JsonObject,
        meta: JsonObject,
        stats: Map<String, Double>,
        modifiers: Map<String, Double>,
        games: Map<String, GameState>,
        side: Int,
    ): Player {
        val nflTeam = strOr(meta["teamId"])
        val game = games[nflTeam]
        val projected = optFloat(entry["projectedPoints"])
        val points = if (stats.isNotEmpty()) Scoring.score(stats, modifiers) else 0.0
        return Player(
            playerId = strOr(entry["id"]),
            name = if (truthy(meta["name"])) pyStr(meta["name"]!!) else strOr(entry["id"]),
            slot = strOr(entry["position"]),
            points = points,
            projected = projected,
            liveProjected = Relay.liveProjection(points, projected, game),
            statLine = Scoring.statLine(stats),
            gameNote = game?.noteFor(nflTeam) ?: "",
            kickoff = game?.let { Relay.epoch(it.startTime) },
            hasBall = game?.hasBall(nflTeam) ?: false,
            redZone = game?.inRedZone(nflTeam) ?: false,
            status = strOr(entry["status"]).uppercase(),
            teamSide = side,
            stats = stats,
            nflTeam = strOr(meta["team"]),
            // A club with no row in the games feed reads `unknown`, never `bye`: a real bye and a feed
            // that arrived short look identical from here.
            gameState = game?.state ?: "unknown",
        )
    }

    private fun roster(
        team: JsonObject,
        service: JsonObject,
        statsByPlayer: Map<String, Map<String, Double>>,
        defenseByTeam: Map<String, Map<String, Double>>,
        modifiers: Map<String, Double>,
        games: Map<String, GameState>,
        side: Int,
    ): List<Player> {
        val metaAll = service.obj("players") ?: JsonObject(emptyMap())
        return team.list("players").filterIsInstance<JsonObject>().map { entry ->
            val pid = strOr(entry["id"])
            val meta = metaAll[pid] as? JsonObject ?: JsonObject(emptyMap())
            val stats = if (isDefense(entry, meta)) {
                defenseByTeam[strOr(meta["teamId"])] ?: emptyMap()
            } else {
                statsByPlayer[pid] ?: emptyMap()
            }
            player(entry, meta, stats, modifiers, games, side)
        }
    }

    /** A fantasy team's live total: its **starters**, summed — bench points never count. */
    private fun team(team: JsonObject, roster: List<Player>): Team {
        val starters = roster.filter { it.starter }
        val live = if (starters.isNotEmpty()) Py.round(Py.sumOf(starters) { it.liveProjected ?: 0.0 }, 2) else null
        return Team(
            teamId = strOr(team["id"]),
            name = strOr(team["name"]),
            points = Py.round(Py.sumOf(starters) { it.points ?: 0.0 }, 2),
            projected = optFloat(team["projectedPoints"]),
            liveProjected = live,
            // "in" and "delayed" can still move the score; a missing game (`unknown`) does not.
            remaining = starters.count { it.gameState in setOf("pre", "in", "delayed") },
            remainingVar = Py.round(
                Py.sumOf(starters) {
                    val left = maxOf(0.0, (it.liveProjected ?: 0.0) - (it.points ?: 0.0))
                    left * left
                },
                4,
            ),
        )
    }

    private fun service(payload: JsonObject): JsonObject =
        payload["service"] as? JsonObject ?: throw bad("redzone payload has no 'service' object")

    private fun league(service: JsonObject, leagueId: String): JsonObject {
        val leagues = service.obj("leagues") ?: JsonObject(emptyMap())
        return leagues[leagueId] as? JsonObject
            ?: throw bad("league $leagueId not present in the redzone payload (got ${leagues.keys.sorted().ifEmpty { "nothing" }})")
    }

    /** The league's display name. */
    fun leagueName(seed: JsonObject, leagueId: String): String = strOr(league(service(seed), leagueId)["name"])

    /**
     * `{team_id: name}` for the team picker, by numeric id. Anonymous access reports `isOwned: false` for
     * every team, so which team is "mine" has to be chosen, not detected.
     */
    fun teamChoices(seed: JsonObject, leagueId: String): Map<String, String> {
        val teams = league(service(seed), leagueId).obj("teams") ?: return emptyMap()
        return teams.entries
            .sortedBy { it.key.toLongOrNull() ?: Long.MAX_VALUE }
            .associate { (tid, team) ->
                val name = (team as? JsonObject)?.get("name")
                tid to if (truthy(name)) pyStr(name!!) else tid
            }
    }

    /** Fold parsed matchups into a [LeagueSnapshot], carrying the raw stat line through. */
    fun toSnapshot(matchups: List<Matchup>, week: Int, takenAt: Double, leagueId: String = "0"): LeagueSnapshot {
        val players = LinkedHashMap<String, PlayerSnapshot>()
        matchups.forEachIndexed { index, matchup ->
            val matchupId = "w$week.m${index + 1}"
            for (player in matchup.players) {
                val team = matchup.team(player.teamSide)
                val key = "$leagueId.p.${player.playerId}"
                players[key] = PlayerSnapshot(
                    playerKey = key,
                    name = player.name,
                    points = player.points ?: 0.0,
                    teamKey = "$leagueId.t.${team.teamId}",
                    matchupId = matchupId,
                    nflTeam = player.nflTeam.ifEmpty { null },
                    position = player.slot,
                    selectedPosition = player.slot,
                    stats = player.stats,
                )
            }
        }
        return LeagueSnapshot(week, takenAt, players)
    }

    // Accept either a bare id or a pasted URL. The `/f1/` anchor is tried FIRST: an archived URL is
    // `/2025/f1/476807`, and a looser pattern returns the season year as the league id.
    private val LEAGUE_IN_URL = Regex("""/f1/(\d{3,10})""")
    private val BARE_ID = Regex("""\b(\d{3,10})\b""")

    /** Pull the numeric league id out of an id or a pasted league URL. */
    fun extractLeagueId(raw: String?): String? {
        val text = (raw ?: "").trim()
        if (text.isEmpty()) return null
        if (Py.isDigits(text)) return text
        LEAGUE_IN_URL.find(text)?.let { return it.groupValues[1] }
        return BARE_ID.find(text)?.groupValues?.get(1)
    }

    // --- Python's view of JSON values -------------------------------------------------------------

    private fun bad(message: String) = IllegalArgumentException(message)

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** `x.get(key) or []`. */
    private fun JsonObject.list(key: String): List<JsonElement> = this[key] as? JsonArray ?: emptyList()

    /** Python truthiness of a JSON value. */
    internal fun truthy(el: JsonElement?): Boolean = when (el) {
        null, JsonNull -> false
        is JsonObject -> el.isNotEmpty()
        is JsonArray -> el.isNotEmpty()
        is JsonPrimitive -> when {
            el.isString -> el.content.isNotEmpty()
            el.booleanOrNull != null -> el.booleanOrNull!!
            else -> (el.doubleOrNull ?: 0.0) != 0.0
        }
    }

    /** `str(x)` of a JSON value. */
    internal fun pyStr(el: JsonElement): String = when (el) {
        JsonNull -> "None"
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.booleanOrNull != null -> if (el.booleanOrNull!!) "True" else "False"
            else -> el.content
        }
        else -> el.toString()
    }

    /** `str(x or "")`. */
    private fun strOr(el: JsonElement?): String = if (truthy(el)) pyStr(el!!) else ""

    /** `int(x)` of a JSON number or numeric string. */
    private fun intOf(el: JsonElement): Int? {
        val p = el as? JsonPrimitive ?: return null
        if (p.isString) return Py.intOrNull(p.content)
        return p.content.toIntOrNull() ?: p.doubleOrNull?.toInt()
    }

    private fun floatOf(el: JsonElement): Double {
        val p = el as? JsonPrimitive ?: throw bad("not a number: $el")
        return Py.floatOrNull(p.content) ?: throw bad("not a number: $el")
    }

    /** `float(x) if x not in (None, "") else None`. */
    private fun optFloat(el: JsonElement?): Double? {
        if (el == null || el == JsonNull) return null
        if (el is JsonPrimitive && el.isString && el.content.isEmpty()) return null
        return floatOf(el)
    }
}
