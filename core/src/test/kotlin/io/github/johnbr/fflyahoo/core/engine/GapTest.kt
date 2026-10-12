package io.github.johnbr.fflyahoo.core.engine

import io.github.johnbr.fflyahoo.core.Golden
import io.github.johnbr.fflyahoo.core.client.Fetcher
import io.github.johnbr.fflyahoo.core.client.LeagueSource
import io.github.johnbr.fflyahoo.core.client.RelayClient
import io.github.johnbr.fflyahoo.core.client.SeedClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * The app stops polling while it is off screen; the integration never does. A score that changed in
 * that gap happened then, not at the poll that finally noticed it, so it must not raise a banner.
 * (Seen live 2026-10-11: Bal's fourth-quarter touchdown bannered in overtime when the tablet woke.)
 */
class GapTest {
    private val gameRow = "g|20260909026|17|26|P|26|3|14:52|7|0|"
    private val baseGames = Golden.fixture("yahoo_relay_games_2026_w1.txt")

    /** Polls the w1 fixtures at each `(now, New England's score)`; returns game 26's banner after each. */
    private fun bannersAfter(vararg polls: Pair<Double, Int>): List<String?> = runBlocking {
        var games = baseGames
        val bodies = mapOf(
            "redzone" to { Golden.fixture("yahoo_redzone_2026_w1.json") },
            "stats.txt" to { Golden.fixture("yahoo_relay_stats_2026_w1.txt") },
            "games.txt" to { games },
            "players.txt" to { Golden.fixture("yahoo_relay_players_2026_w1.txt") },
            "plays-26" to { Golden.fixture("yahoo_relay_plays_26_2026_w1.txt") },
        )
        val fetch = Fetcher { url -> bodies.entries.first { it.key in url }.value() }
        var now = 0.0
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = LeagueEngine(
            source = LeagueSource(SeedClient(fetch, "999999"), RelayClient(fetch)),
            history = object : HistoryStore {
                override suspend fun load(): String? = null
                override suspend fun save(json: String) {}
            },
            clock = { now },
            scope = scope,
            dispatcher = dispatcher,
            chaseBetweenPolls = false,
        )
        try {
            polls.map { (at, score) ->
                now = at
                games = baseGames.replace(gameRow, "g|20260909026|17|26|P|26|3|14:52|$score|0|")
                check(games != baseGames || score == 7) { "the fixture row must be found" }
                engine.refresh()
                engine.state.value.nfl.games.first { it.gameId == "20260909026" }.scoreBanner?.kind
            }
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `a touchdown between polls on schedule raises a banner`() {
        val t = 1_789_005_218.0
        assertEquals(listOf(null, "TOUCHDOWN"), bannersAfter(t to 7, t + 10 to 14))
    }

    @Test
    fun `a touchdown that happened while the app was not polling does not`() {
        val t = 1_789_005_218.0
        val banners = bannersAfter(t to 7, t + 300 to 14)
        assertNull(banners[1], "the score changed during a five-minute gap, not at this poll")
    }

    @Test
    fun `after the gap, the next touchdown on schedule is bannered again`() {
        val t = 1_789_005_218.0
        assertEquals(listOf(null, null, "TOUCHDOWN"), bannersAfter(t to 7, t + 300 to 14, t + 310 to 21))
    }
}
