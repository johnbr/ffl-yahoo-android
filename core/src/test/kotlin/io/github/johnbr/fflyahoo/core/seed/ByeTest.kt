package io.github.johnbr.fflyahoo.core.seed

import io.github.johnbr.fflyahoo.core.Golden
import io.github.johnbr.fflyahoo.core.history.History
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A club on a bye, as ESPN's schedule says: the lineup's game line reads "Bye". */
class ByeTest {
    private val schedule = History.parseEspnSchedule(History.parse(Golden.fixture("espn_schedule_2026.json")))

    private fun maye(games: String, byes: Set<String>) = Seed.leagueFromPayloads(
        Seed.parse(Golden.fixture("yahoo_redzone_2026_w1.json")),
        Golden.fixture("yahoo_relay_stats_2026_w1.txt"),
        games,
        "999999",
        now = 1000.0,
        byes = byes,
    ).matchups.flatMap { it.players }.first { it.playerId == "40881" }

    @Test
    fun `a week's byes are the clubs without a game`() {
        assertTrue("17" in History.byeClubs(schedule, 11)) // New England
        assertTrue("12" in History.byeClubs(schedule, 5)) // Kansas City
        assertEquals(emptySet<String>(), History.byeClubs(schedule, 1))
        // A week nobody plays — a playoff week, say — is not thirty-two byes.
        assertEquals(emptySet<String>(), History.byeClubs(schedule, 25))
    }

    @Test
    fun `a club on the schedule's bye reads bye`() {
        val p = maye("", setOf("17"))
        assertEquals("bye", p.gameState)
        assertEquals("Bye", p.gameNote)
        assertEquals(emptyMap<String, Double>(), p.stats)
    }

    @Test
    fun `a game in the feed outranks the schedule`() {
        val p = maye(Golden.fixture("yahoo_relay_games_2026_w1.txt"), setOf("17"))
        assertNotEquals("bye", p.gameState)
    }

    @Test
    fun `without the schedule a missing game is still unknown`() {
        val p = maye("", emptySet())
        assertEquals("unknown", p.gameState)
        assertEquals("", p.gameNote)
    }
}
