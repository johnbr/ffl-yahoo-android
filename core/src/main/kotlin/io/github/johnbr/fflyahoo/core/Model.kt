package io.github.johnbr.fflyahoo.core

import io.github.johnbr.fflyahoo.core.relay.GameState

/** Lineup slots whose points do not count toward the fantasy team's score. */
val BENCH_SLOTS = setOf("BN", "IR", "IR+", "IR-R", "NA")

/** One rostered player at one poll. Port of `yahoo_web.WebPlayer`. */
data class Player(
    /** Yahoo's numeric player id. Stable across weeks. */
    val playerId: String,
    val name: String,
    /** Lineup slot — `BN` for bench, `QB`/`WR`/`W/R`/... for starters. */
    val slot: String,
    val points: Double?,
    val projected: Double?,
    /** `1 Rush TD, 123 Pass Yds, 1 Pass TD`. Empty before kickoff. */
    val statLine: String = "",
    /** The NFL-game blurb, e.g. `Final W 26-7 @ Pit`. */
    val gameNote: String = "",
    /** 0 = home (left) team, 1 = away (right) team. */
    val teamSide: Int = 0,
    val stats: Map<String, Double> = emptyMap(),
    /** NFL club abbreviation, e.g. `NE`. */
    val nflTeam: String = "",
    val hasBall: Boolean = false,
    val redZone: Boolean = false,
    /** Injury designation: `Q`, `D`, `O`, `IR`... */
    val status: String = "",
    /** What the player is on pace for — see [io.github.johnbr.fflyahoo.core.relay.Relay.liveProjection]. */
    val liveProjected: Double? = null,
    /** Kickoff of this player's NFL game, epoch seconds. */
    val kickoff: Long? = null,
    /** `pre` / `in` / `post` / `delayed` / `unknown` — from the relay's status letter. */
    val gameState: String = "unknown",
) {
    val starter: Boolean get() = slot.uppercase() !in BENCH_SLOTS
}

/** One fantasy team's side of a matchup. Port of `yahoo_web.WebTeam`. */
data class Team(
    val teamId: String,
    val name: String,
    val points: Double? = null,
    val projected: Double? = null,
    /** The starters' live projections, summed. Matches Yahoo's "Proj Pts". */
    val liveProjected: Double? = null,
    /** Starters whose game has not finished. Zero on both sides makes the result final. */
    val remaining: Int? = null,
    /** Sum of squares of the starters' still-to-come projections — the win probability's spread. */
    val remainingVar: Double = 0.0,
)

/** Both sides of one head-to-head matchup, with full rosters. `teams.first` is home. */
data class Matchup(
    val week: Int,
    val teams: Pair<Team, Team>,
    val players: List<Player> = emptyList(),
) {
    fun team(side: Int): Team = if (side == 0) teams.first else teams.second
    fun roster(side: Int): List<Player> = players.filter { it.teamSide == side }
}

/** One refresh's worth of league state. Port of `web_client.LeagueData`. */
data class LeagueData(
    val week: Int,
    val matchups: List<Matchup> = emptyList(),
    val standings: List<Pair<Team, Team>> = emptyList(),
    val fetchedAt: Double = 0.0,
    /** True when no matchup could be built — scores are shown but incomplete. */
    val partial: Boolean = false,
    /** NFL games in progress. */
    val activeGames: Int = 0,
    /** Changes whenever a live game's clock or score moves. */
    val liveTick: String = "",
    /** `{club abbreviation: plays-feed id}` for games in progress. */
    val playsFeeds: Map<String, String> = emptyMap(),
    /** The week's NFL slate, one entry per game, kickoff order. */
    val nflGames: List<GameState> = emptyList(),
    /**
     * NFL clubs currently playing, by abbreviation. `null` means the games feed was unavailable, which
     * is deliberately distinct from an empty set — "we don't know" is not "nothing is live".
     */
    val liveClubs: Set<String>? = null,
)

/** This client's failures. */
open class YahooError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A transient problem — network, timeout, or an unexpected payload. */
class FetchFailed(message: String, cause: Throwable? = null) : YahooError(message, cause)

/** Beyond this, cached data is too old to show as if it were live. */
const val STALE_AFTER_SECONDS = 900.0
