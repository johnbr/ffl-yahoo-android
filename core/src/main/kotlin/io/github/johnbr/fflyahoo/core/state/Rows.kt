package io.github.johnbr.fflyahoo.core.state

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// What the screens consume. These are the sensor attributes and WebSocket payloads of the Home
// Assistant integration, typed; serialised with snake_case names they compare 1:1 against the
// integration's own dicts (see the parity tests).

@Serializable
data class TeamRow(
    val teamId: String,
    val name: String,
    val points: Double?,
    val projected: Double?,
    val liveProjected: Double?,
    val remaining: Int?,
)

@Serializable
data class PlayRow(
    val eventId: String,
    /** The full line: Yahoo's play sentence when matched, else the stat delta. */
    val text: String,
    /** The compact player-side form: `T. Etienne Jr. 1 rec, 1 yd`. */
    val shortText: String,
    val shortWho: String,
    val shortWhat: String,
    val statDelta: String,
    val player: String,
    val teamKey: String,
    val matchupId: String?,
    val delta: Double,
    val points: Double,
    val timestamp: Double,
    val correction: Boolean,
    val starter: Boolean,
    val enriched: Boolean,
    /** `home` / `away` — which side of the matchup row scored it. Only on a row's last play. */
    val side: String? = null,
)

@Serializable
data class MatchupRow(
    val matchupId: String,
    val index: Int,
    val home: TeamRow,
    val away: TeamRow,
    /** Team id of the side ahead, or null on a tie. */
    val leader: String?,
    /** Home's chance of winning, 0-1. */
    val winProb: Double?,
    val final: Boolean,
    val hasRoster: Boolean,
    val lastPlay: PlayRow?,
)

@Serializable
data class PlayerRow(
    val playerId: String,
    val name: String,
    val shortName: String,
    val slot: String,
    val points: Double?,
    val projected: Double?,
    val liveProjected: Double?,
    val nflTeam: String,
    val hasBall: Boolean,
    val redZone: Boolean,
    val status: String,
    val statLine: String,
    val game: String,
    val gameState: String,
    val kickoff: Long?,
)

@Serializable
data class RosterSide(
    val teamId: String,
    val name: String,
    val points: Double?,
    val projected: Double?,
    val liveProjected: Double?,
    val remaining: Int?,
    val starters: List<PlayerRow>,
    val bench: List<PlayerRow>,
)

@Serializable
data class MatchupDetail(val matchupId: String?, val sides: List<RosterSide>, val week: Int? = null)

@Serializable
data class ScoreBanner(val kind: String, val abbr: String, val at: Double, val until: Double)

@Serializable
data class NflSide(val teamId: String, val abbr: String, val score: Int?, val hasBall: Boolean, val redZone: Boolean)

@Serializable
data class NflGameRow(
    val gameId: String,
    val playsId: String,
    val state: String,
    val clockText: String,
    val situation: String,
    val ballOn: String,
    val yardsToGoal: Int?,
    val lastPlay: String,
    val lastPlayProvisional: Boolean,
    val scoreBanner: ScoreBanner?,
    val startTime: Long?,
    val away: NflSide,
    val home: NflSide,
)

@Serializable
data class NflPlayRow(
    val playId: String,
    val text: String,
    val shortText: String,
    val situation: String,
    val period: String,
    val clock: String,
    /** A snap whose text is still on its way (see `PendingPlays`); only ever written when true. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val provisional: Boolean = false,
)

@Serializable
data class Scoreboard(
    val leagueId: String,
    val leagueName: String,
    val week: Int?,
    val matchups: List<MatchupRow>,
    val lastPlay: PlayRow?,
    val recentPlays: List<PlayRow>,
    val activeGames: Int,
    val liveTick: String,
    val partial: Boolean,
)

@Serializable
data class NflGames(
    val leagueId: String,
    val games: List<NflGameRow>,
    val activeGames: Int,
    val liveTick: String,
    val totalGames: Int,
)

@Serializable
data class MyTeam(
    val matchupId: String,
    val index: Int,
    val me: TeamRow,
    val opponent: TeamRow,
    val winning: Boolean,
)
