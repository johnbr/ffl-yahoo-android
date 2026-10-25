package io.github.johnbr.fflyahoo.core.state

import io.github.johnbr.fflyahoo.core.LeagueData
import io.github.johnbr.fflyahoo.core.Player
import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.Team
import io.github.johnbr.fflyahoo.core.plays.PlayFeed
import io.github.johnbr.fflyahoo.core.plays.Plays
import io.github.johnbr.fflyahoo.core.plays.ScoringEvent
import io.github.johnbr.fflyahoo.core.relay.GameState
import io.github.johnbr.fflyahoo.core.relay.Relay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turn a fetched league into what the screens consume. Port of `league_state.py`: pure functions, so
 * the shapes are pinned by tests rather than discovered when a screen renders wrong.
 */
object LeagueState {
    /**
     * The live poll. Asking the relay between polls is not free: a 2 s games-feed watch (v0.7.0) was
     * withdrawn after the relay started refusing the network it ran from (HTTP 999, "Request denied") on
     * its first full Sunday, 2026-10-25. A `304` is still a request.
     */
    const val SCAN_INTERVAL_LIVE_SECONDS = 10
    const val SCAN_INTERVAL_NEAR_GAME_SECONDS = 300
    const val SCAN_INTERVAL_IDLE_SECONDS = 1800

    /** How many plays ride along with the scoreboard. */
    const val ATTR_RECENT_PLAYS = 10

    /**
     * Seconds until the next refresh, from what the games are actually doing. A slate where EVERY player
     * is unknown is what a missing games feed looks like, so it may never slow the cadence ([previous]).
     */
    fun pollInterval(data: LeagueData?, previous: Int? = null): Int {
        if (data == null) return SCAN_INTERVAL_NEAR_GAME_SECONDS
        val states = data.matchups.flatMap { m -> m.players.map { it.gameState } }.toSet()
        if ("in" in states) return SCAN_INTERVAL_LIVE_SECONDS
        if (previous != null && states.isNotEmpty() && states.all { it == "unknown" }) {
            return minOf(previous, SCAN_INTERVAL_NEAR_GAME_SECONDS)
        }
        if (states.any { it in setOf("pre", "unknown", "delayed") }) return SCAN_INTERVAL_NEAR_GAME_SECONDS
        return SCAN_INTERVAL_IDLE_SECONDS
    }

    /** How much a still-to-come projection can miss by, as a fraction of itself. */
    const val PROJECTION_CV = 0.75

    /** Home team's chance of winning, 0-1: a normal approximation on the difference of live projections. */
    fun winProbability(home: Team, away: Team): Double? {
        val h = home.liveProjected ?: return null
        val a = away.liveProjected ?: return null
        val lead = h - a
        val sigma = PROJECTION_CV * sqrt(home.remainingVar + away.remainingVar)
        if (sigma <= 0) return if (lead > 0) 1.0 else if (lead < 0) 0.0 else 0.5
        return Py.round(0.5 * (1.0 + erf(lead / (sigma * sqrt(2.0)))), 4)
    }

    /** The error function, to ~1e-15 — Kotlin has none. Series near zero, continued fraction beyond. */
    fun erf(x: Double): Double {
        if (x.isNaN()) return x
        val ax = abs(x)
        if (ax < 2.0) {
            // erf(x) = 2/sqrt(pi) * sum (-1)^n x^(2n+1) / (n! (2n+1))
            var term = ax
            var sum = ax
            val x2 = ax * ax
            var n = 0
            while (abs(term) > 1e-17 * abs(sum)) {
                n++
                term *= -x2 / n
                sum += term / (2 * n + 1)
            }
            val result = 2.0 / sqrt(Math.PI) * sum
            return if (x < 0) -result else result
        }
        if (ax > 6.0) return if (x < 0) -1.0 else 1.0
        // erfc(x) = exp(-x^2)/sqrt(pi) * 1/(x + (1/2)/(x + 1/(x + (3/2)/(x + 2/(x + ...))))), by Lentz.
        val tiny = 1e-300
        var f = ax
        var c = ax
        var d = 0.0
        var k = 1
        while (k < 500) {
            val a = k / 2.0
            d = ax + a * d
            d = if (d == 0.0) tiny else d
            c = ax + a / c
            c = if (c == 0.0) tiny else c
            d = 1.0 / d
            val delta = c * d
            f *= delta
            if (abs(delta - 1.0) < 1e-16) break
            k++
        }
        val erfc = exp(-ax * ax) / sqrt(Math.PI) / f
        val result = 1.0 - erfc
        return if (x < 0) -result else result
    }

    fun teamRow(team: Team) = TeamRow(team.teamId, team.name, team.points, team.projected, team.liveProjected, team.remaining, team.record)

    /** Nobody on either side left to play. A tier that cannot count never declares a winner. */
    fun matchupFinal(home: Team, away: Team): Boolean = home.remaining == 0 && away.remaining == 0

    /** Compact per-matchup summaries, each with its own last scoring play when a [feed] is supplied. */
    fun matchupRows(data: LeagueData, feed: PlayFeed? = null): List<MatchupRow> =
        data.standings.mapIndexed { index, (home, away) ->
            val matchupId = "w${data.week}.m${index + 1}"
            var leader: String? = null
            if (home.points != null && away.points != null) {
                if (home.points > away.points) leader = home.teamId
                else if (away.points > home.points) leader = away.teamId
            }
            // A play must not outlive the game it happened in.
            val last = feed?.lastPlay(matchupId = matchupId, startersOnly = true, nflTeams = data.liveClubs)
            MatchupRow(
                matchupId = matchupId,
                index = index + 1,
                home = teamRow(home),
                away = teamRow(away),
                leader = leader,
                winProb = winProbability(home, away),
                final = matchupFinal(home, away),
                hasRoster = index < data.matchups.size,
                lastPlay = last?.let { playRow(it).copy(side = sideOf(it.teamKey, home, away)) },
            )
        }

    /** `home`/`away` for a fantasy team key (`{league_id}.t.{team_id}`), or null if neither. */
    private fun sideOf(teamKey: String?, home: Team, away: Team): String? {
        if (teamKey.isNullOrEmpty()) return null
        if (teamKey.endsWith(".t.${home.teamId}")) return "home"
        if (teamKey.endsWith(".t.${away.teamId}")) return "away"
        return null
    }

    /**
     * The NFL clubs (abbreviations, as the seed writes them) of both sides' starters in one matchup
     * (0-based index): the games that can still move its score. Bench points never count.
     */
    fun matchupClubs(data: LeagueData?, matchupIndex: Int): Set<String> {
        val matchup = data?.matchups?.getOrNull(matchupIndex) ?: return emptySet()
        return matchup.players.filter { it.starter && it.nflTeam.isNotEmpty() }.mapTo(LinkedHashSet()) { it.nflTeam }
    }

    /** Both rosters for one matchup (0-based index) — the expand-a-row payload. */
    fun playerRows(data: LeagueData, matchupIndex: Int): MatchupDetail {
        if (matchupIndex !in data.matchups.indices) return MatchupDetail(null, emptyList())
        val matchup = data.matchups[matchupIndex]
        val sides = (0..1).map { side ->
            val team = matchup.team(side)
            val roster = matchup.roster(side)
            RosterSide(
                team.teamId, team.name, team.points, team.projected, team.liveProjected, team.remaining,
                starters = roster.filter { it.starter }.map(::playerRow),
                bench = roster.filterNot { it.starter }.map(::playerRow),
                record = team.record,
            )
        }
        return MatchupDetail("w${data.week}.m${matchupIndex + 1}", sides)
    }

    fun playerRow(player: Player) = PlayerRow(
        playerId = player.playerId,
        name = player.name,
        // "J. Allen", the form the play line uses. A defence is a single word and passes through.
        shortName = Plays.abbreviateName(player.name),
        slot = player.slot,
        points = player.points,
        projected = player.projected,
        liveProjected = player.liveProjected,
        nflTeam = player.nflTeam,
        hasBall = player.hasBall,
        redZone = player.redZone,
        status = player.status,
        statLine = player.statLine,
        game = player.gameNote,
        gameState = player.gameState,
        kickoff = player.kickoff,
    )

    /** One scoring event, as the row and history want it. */
    fun playRow(event: ScoringEvent): PlayRow {
        val (who, what) = Plays.shortParts(event)
        return PlayRow(
            eventId = event.eventId,
            text = Plays.describe(event),
            shortText = Plays.shortDescribe(event),
            shortWho = who,
            shortWhat = what,
            statDelta = event.statDelta,
            player = event.playerName,
            teamKey = event.teamKey,
            matchupId = event.matchupId,
            delta = Py.round(event.delta, 2),
            points = Py.round(event.newPoints, 2),
            timestamp = event.timestamp,
            correction = event.correction,
            starter = event.starter,
            enriched = event.enriched,
        )
    }

    // -- the NFL slate -------------------------------------------------------------------------------

    /** What to print where the clock goes: kickoff (formatted by the screen), quarter, or Final. */
    fun clockTextOf(game: GameState): String = when (game.state) {
        "post" -> "Final"
        "pre" -> ""
        "delayed" -> "Delayed · ${Relay.clockText(game.period, game.clock)}".trimEnd(' ', '·')
        else -> Relay.clockText(game.period, game.clock)
    }

    /**
     * How far the offence has to go, or null when the feed has no real spot: only a live game, not at
     * half time, strictly between 0 and 100, carried by one of the two clubs playing.
     */
    fun yardsToGoal(game: GameState): Int? {
        if (game.state != "in") return null
        if (Relay.isHalftime(game.period, game.clock)) return null
        val toGoal = (if (game.yardsToGoal.isEmpty()) 0 else Py.intOrNull(game.yardsToGoal)) ?: return null
        if (toGoal !in 1..99) return null
        if (game.teamWithBall != game.away && game.teamWithBall != game.home) return null
        return toGoal
    }

    /** `DAL 19` — the yard line the way a broadcast says it; past midfield it is the defence's number. */
    fun ballSpot(game: GameState): String {
        val toGoal = yardsToGoal(game) ?: return ""
        if (toGoal == 50) return "50"
        val withBall = game.teamWithBall
        if (toGoal > 50) return "${Relay.teamAbbr(withBall)} ${100 - toGoal}"
        val other = if (withBall == game.away) game.home else game.away
        return "${Relay.teamAbbr(other)} $toGoal"
    }

    /** `2nd & 7` — empty unless a live game has a real ball spot. */
    fun situation(game: GameState): String {
        if (game.state != "in" || ballSpot(game).isEmpty()) return ""
        return downAndDistance(game.down, game.distance, yardsToGoal(game)?.toString())
    }

    /** `2nd & 7`, or `2nd & goal` whenever the distance reaches the goal line. Empty without a down. */
    fun downAndDistance(down: String?, distance: String?, toGoal: String? = null): String {
        fun int(text: String?): Int? = if (text.isNullOrEmpty()) 0 else Py.intOrNull(text)
        return downAndDistance(int(down) ?: return "", int(distance) ?: return "", int(toGoal) ?: return "")
    }

    fun downAndDistance(down: Int, distance: Int, toGoal: Int): String {
        if (down == 0) return ""
        val ordinal = when (down) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            4 -> "4th"
            else -> "${down}th"
        }
        if (distance <= 0 || (toGoal in 1..distance)) return "$ordinal & goal"
        return "$ordinal & $distance"
    }

    private fun nflSide(game: GameState, club: String, score: String) = NflSide(
        teamId = club,
        abbr = Relay.teamAbbr(club),
        score = Py.intOrNull(score),
        hasBall = game.hasBall(club),
        redZone = game.inRedZone(club),
    )

    /**
     * The week's NFL games. [lastPlays] maps a plays-feed id to that game's newest play; [pending] to a
     * provisional result while the text is out; [banners] maps a game id to its scoring banner.
     */
    fun nflGameRows(
        data: LeagueData?,
        lastPlays: Map<String, String> = emptyMap(),
        pending: Map<String, String> = emptyMap(),
        banners: Map<String, ScoreBanner> = emptyMap(),
    ): List<NflGameRow> {
        if (data == null) return emptyList()
        return data.nflGames.map { game ->
            val playsId = game.playsId
            val provisional = pending[playsId] ?: ""
            NflGameRow(
                gameId = game.gameId,
                playsId = playsId,
                state = game.state,
                clockText = clockTextOf(game),
                situation = situation(game),
                ballOn = ballSpot(game),
                yardsToGoal = yardsToGoal(game),
                lastPlay = provisional.ifEmpty { lastPlays[playsId] ?: "" },
                lastPlayProvisional = provisional.isNotEmpty(),
                scoreBanner = banners[game.gameId],
                startTime = game.startTime.trim().toLongOrNull(),
                away = nflSide(game, game.away, game.awayScore),
                home = nflSide(game, game.home, game.homeScore),
            )
        }
    }

    fun nflGames(
        data: LeagueData?,
        leagueId: String,
        lastPlays: Map<String, String> = emptyMap(),
        pending: Map<String, String> = emptyMap(),
        banners: Map<String, ScoreBanner> = emptyMap(),
    ): NflGames {
        val rows = nflGameRows(data, lastPlays, pending, banners)
        return NflGames(leagueId, rows, data?.activeGames ?: 0, data?.liveTick ?: "", rows.size)
    }

    fun scoreboard(data: LeagueData?, feed: PlayFeed, leagueId: String, leagueName: String = ""): Scoreboard {
        if (data == null) return Scoreboard(leagueId, leagueName, null, emptyList(), null, emptyList(), 0, "", false)
        val last = feed.lastPlay(startersOnly = true, nflTeams = data.liveClubs)
        return Scoreboard(
            leagueId = leagueId,
            leagueName = leagueName,
            week = data.week,
            matchups = matchupRows(data, feed),
            lastPlay = last?.let(::playRow),
            recentPlays = feed.recent(ATTR_RECENT_PLAYS, startersOnly = true, nflTeams = data.liveClubs).map(::playRow),
            activeGames = data.activeGames,
            liveTick = data.liveTick,
            partial = data.partial,
        )
    }

    /** One team's row and its matchup — the My Matchup screen. */
    fun findTeam(data: LeagueData?, teamId: String?): MyTeam? {
        if (data == null || teamId.isNullOrEmpty()) return null
        for (row in matchupRows(data)) {
            if (row.home.teamId == teamId) return MyTeam(row.matchupId, row.index, row.home, row.away, row.leader == teamId)
            if (row.away.teamId == teamId) return MyTeam(row.matchupId, row.index, row.away, row.home, row.leader == teamId)
        }
        return null
    }
}
