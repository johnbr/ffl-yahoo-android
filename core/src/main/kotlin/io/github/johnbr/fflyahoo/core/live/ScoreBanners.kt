package io.github.johnbr.fflyahoo.core.live

import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.relay.GameState
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.state.ScoreBanner

/**
 * The NFL screen's scoring banner — TOUCHDOWN, FIELD GOAL, SAFETY. Port of `score_banners.py`.
 *
 * Decided in the engine so a screen opened ten seconds after the jump shows the same banner. It stays
 * up until the play AFTER the scoring play lands in the play feed (the PAT, the kickoff), at least
 * [MIN_BANNER_SECONDS] and at most [BANNER_SECONDS].
 */
class ScoreBanners {
    private data class Seen(val awayScore: Int?, val homeScore: Int?, val team: String)

    private val seen = LinkedHashMap<String, Seen>()
    private val banners = LinkedHashMap<String, ScoreBanner>()

    /** Per banner, the newest play-feed row from BEFORE the score. */
    private val floors = HashMap<String, Int?>()

    /**
     * Take one poll's view of the slate. A game seen for the first time is the baseline, never a banner.
     * [floorsByGame] maps a game id to the newest row its play feed had as of the last poll.
     */
    fun observe(games: List<GameState>, now: Double, floorsByGame: Map<String, Int> = emptyMap()) {
        val current = HashSet<String>()
        for (game in games) {
            val gameId = game.gameId
            if (gameId.isEmpty()) continue
            current += gameId
            val now_ = Seen(Py.intOrNull(game.awayScore), Py.intOrNull(game.homeScore), game.teamWithBall)
            val before = seen[gameId]
            seen[gameId] = now_
            if (before == null || game.state != "in") continue
            val (kind, club) = scoringKind(before.awayScore, before.homeScore, before.team, now_.awayScore, now_.homeScore, game.away, game.home)
                ?: continue
            banners[gameId] = ScoreBanner(kind, Relay.teamAbbr(club), now, now + BANNER_SECONDS)
            floors[gameId] = floorsByGame[gameId]
        }
        seen.keys.retainAll(current)
        for ((gameId, banner) in banners.entries.toList()) {
            if (gameId !in current || banner.until <= now) drop(gameId)
        }
    }

    /**
     * Read a game's play feed — `(sequence, play type)` per row. Takes the banner down once a play has
     * landed after the scoring play. Returns whether a banner came down.
     */
    fun plays(gameId: String, rows: List<Pair<Int, String>>, now: Double): Boolean {
        val banner = banners[gameId] ?: return false
        val landed = rows.filter { it.second !in NOT_A_PLAY }.map { it.first }.sorted()
        val floor = floors[gameId]
        if (floor == null) {
            if (landed.isNotEmpty()) floors[gameId] = landed.last() - 1
            return false
        }
        if (landed.count { it > floor } < 2 || now - banner.at < MIN_BANNER_SECONDS) return false
        drop(gameId)
        return true
    }

    private fun drop(gameId: String) {
        banners.remove(gameId)
        floors.remove(gameId)
    }

    /** `{game_id: banner}` for every banner still up at [now]. */
    fun active(now: Double): Map<String, ScoreBanner> = banners.filterValues { it.until > now }

    companion object {
        const val BANNER_SECONDS = 180.0
        const val MIN_BANNER_SECONDS = 15.0

        /** `24` is a timeout or the two-minute warning; the end of a period (`25`) does count. */
        val NOT_A_PLAY = setOf("24")

        /**
         * `(kind, club)` if one games-feed change was a scoring play: 6-8 a touchdown, 3 a field goal, 2 a
         * safety when it went to the club WITHOUT the ball. More than eight is a gap in the polls.
         */
        fun scoringKind(
            beforeAway: Int?, beforeHome: Int?, beforeTeam: String,
            afterAway: Int?, afterHome: Int?,
            away: String, home: String,
        ): Pair<String, String>? {
            var best: Triple<Int, String, String>? = null
            for ((club, was, now) in listOf(Triple(away, beforeAway, afterAway), Triple(home, beforeHome, afterHome))) {
                if (was == null || now == null || now <= was) continue
                val gain = now - was
                val kind = when {
                    gain in 6..8 -> "TOUCHDOWN"
                    gain == 3 -> "FIELD GOAL"
                    gain == 2 && (beforeTeam == away || beforeTeam == home) && club != beforeTeam -> "SAFETY"
                    else -> ""
                }
                if (kind.isNotEmpty() && (best == null || gain > best.first)) best = Triple(gain, kind, club)
            }
            return best?.let { it.second to it.third }
        }
    }
}
