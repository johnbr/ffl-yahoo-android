package io.github.johnbr.fflyahoo.core.engine

import io.github.johnbr.fflyahoo.core.Golden
import io.github.johnbr.fflyahoo.core.client.Fetcher
import io.github.johnbr.fflyahoo.core.client.LeagueSource
import io.github.johnbr.fflyahoo.core.client.RelayClient
import io.github.johnbr.fflyahoo.core.client.SeedClient
import io.github.johnbr.fflyahoo.core.plays.ScoringEvent
import io.github.johnbr.fflyahoo.core.present.Present
import io.github.johnbr.fflyahoo.core.state.LeagueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * The My Matchup tab lists the live NFL games a starter on either side is playing in. In the w1
 * fixtures only NE at Sea (game 26) is on, and every matchup starts someone from one of them.
 */
class MatchupGamesTest {
    private val baseGames = Golden.fixture("yahoo_relay_games_2026_w1.txt")
    private val neAtSea = "g|20260909026|17|26|P|"
    private val denAtKc = "g|20260914012|7|12|S|"

    /** One poll of the w1 fixtures as [teamId]; returns the live games its matchup tab shows. */
    private fun liveGamesFor(teamId: String, games: String = baseGames): Pair<Set<String>, List<String>> =
        poll(teamId, games) { ui -> ui.myClubs to Present.liveGamesFor(ui.nfl.games, ui.myClubs).map { "${it.away.abbr}@${it.home.abbr}" } }

    /** One poll of the w1 fixtures as [teamId], read by [read]. */
    private fun <T> poll(teamId: String, games: String = baseGames, read: (LeagueUi) -> T): T = runBlocking {
        val bodies = mapOf(
            "redzone" to Golden.fixture("yahoo_redzone_2026_w1.json"),
            "stats.txt" to Golden.fixture("yahoo_relay_stats_2026_w1.txt"),
            "games.txt" to games,
            "players.txt" to Golden.fixture("yahoo_relay_players_2026_w1.txt"),
            "plays-26" to Golden.fixture("yahoo_relay_plays_26_2026_w1.txt"),
        )
        val fetch = Fetcher { url -> bodies.entries.first { it.key in url }.value }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = LeagueEngine(
            source = LeagueSource(SeedClient(fetch, "999999"), RelayClient(fetch)),
            history = object : HistoryStore {
                override suspend fun load(): String? = null
                override suspend fun save(json: String) {}
            },
            clock = { 1_789_005_218.0 },
            scope = scope,
            teamId = teamId,
            dispatcher = dispatcher,
            chaseBetweenPolls = false,
        )
        try {
            engine.refresh()
            read(engine.state.value)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `the clubs are both sides' starters`() {
        // Matchup 1 is team 1 v team 10; neither starts anyone from Seattle.
        val (clubs, games) = liveGamesFor("1")
        assertEquals(
            setOf("Buf", "Chi", "Cle", "Dal", "Det", "Hou", "Ind", "LAC", "LAR", "Mia", "NE", "NO", "SF"),
            clubs,
        )
        assertEquals(listOf("NE@Sea"), games)
        assertEquals(clubs, liveGamesFor("10").first, "the same matchup from the other side")
    }

    @Test
    fun `only games in progress, and only the matchup's`() {
        // NE at Sea finished, Den at KC under way: matchup 1 starts neither club, matchup 2 starts both.
        val games = baseGames.replace(neAtSea, "g|20260909026|17|26|F|").replace(denAtKc, "g|20260914012|7|12|P|")
        check(games != baseGames)
        assertEquals(emptyList<String>(), liveGamesFor("1", games).second)
        assertEquals(listOf("Den@KC"), liveGamesFor("2", games).second)
    }

    @Test
    fun `a play's club finds its game`() {
        // The widgets hold a play until this game is final, so the club has to name a game on the slate.
        val event = ScoringEvent(
            eventId = "e", week = 1, timestamp = 1.0, playerKey = "p", playerName = "K. Walker III", teamKey = "t",
            matchupId = "w1.m1", nflTeam = "Sea", position = "RB", delta = 1.0, oldPoints = 0.0, newPoints = 1.0,
            starter = true, correction = false,
        )
        assertEquals("Sea", LeagueState.playRow(event).nflTeam)
        val states = poll("1") { ui -> listOf("Sea", "SEA", "NE", "Den", "", "XYZ").map { Present.gameFor(ui.nfl.games, it)?.state } }
        assertEquals(listOf("in", "in", "in", "pre", null, null), states)
    }

    @Test
    fun `no team, no games`() {
        val (clubs, games) = liveGamesFor("")
        assertTrue(clubs.isEmpty())
        assertTrue(games.isEmpty())
    }
}
