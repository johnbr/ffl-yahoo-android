package io.github.johnbr.fflyahoo.core.client

import io.github.johnbr.fflyahoo.core.Golden
import io.github.johnbr.fflyahoo.core.history.History
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * Fetching and caching a player's season, against the captured fixtures — the integration's
 * `test_history_client.py`, in Kotlin. The fake serves the week-1 seed relabelled as whichever week is
 * asked for, ESPN's lists and five players' lines, and counts every request.
 */
class SeasonHistoryTest {
    private val league = "999999"
    private val maye = "40881"
    private val now = 1_792_000_000.0

    private inner class Fake(private val fail: List<String> = emptyList()) : Fetcher, HeaderFetcher {
        val calls = ArrayList<String>()
        private val seed = Golden.fixture("yahoo_redzone_2026_w1.json")

        private fun serve(url: String): String {
            calls += url
            if (fail.any { url.startsWith(it) }) throw IOException("fetch failed: $url")
            return when (url) {
                History.espnPlayersUrl(2026) -> Golden.fixture("espn_players_2026.json")
                History.espnScheduleUrl(2026) -> Golden.fixture("espn_schedule_2026.json")
                History.espnStatsUrl(2026) -> Golden.fixture("espn_player_stats_2026.json")
                else -> {
                    val week = (1..18).firstOrNull { url == History.seedWeekUrl(league, it) } ?: error("unexpected fetch $url")
                    relabel(week)
                }
            }
        }

        private fun relabel(week: Int): String {
            val root = History.parse(seed).jsonObject
            val service = root.getValue("service").jsonObject
            val leagues = service.getValue("leagues").jsonObject
            val info = buildJsonObject { put("week", week); put("start", "2026-09-09"); put("end", "2026-09-14") }
            val l = JsonObject(leagues.getValue(league).jsonObject + ("weekInfo" to info))
            return JsonObject(root + ("service" to JsonObject(service + ("leagues" to JsonObject(leagues + (league to l)))))).toString()
        }

        override suspend fun fetch(url: String): String = serve(url)

        override suspend fun fetch(url: String, headers: Map<String, String>): String {
            val filter = headers.getValue("X-Fantasy-Filter")
            if (url == History.espnStatsUrl(2026)) {
                // Actual and projected lines, in the one request.
                val sources = History.parse(filter).jsonObject.getValue("players").jsonObject.getValue("filterStatsForSourceIds")
                assertEquals("[0,1]", sources.jsonObject.getValue("value").toString())
            }
            return serve(url)
        }

        fun seeds() = calls.count { "redzone" in it }
    }

    private fun history(fake: Fake, save: (() -> Unit)? = null) = SeasonHistory(fake, fake, league, save)

    private fun season(h: SeasonHistory, player: String = maye, at: Double = now) = runBlocking { h.player(player, 6, 0.0, at) }

    @Test
    fun `a season from the three sources`() {
        val fake = Fake()
        var saved = 0
        val s = season(history(fake) { saved += 1 })
        assertEquals(Triple("Drake Maye", "NE", "QB"), Triple(s.name, s.nflTeam, s.position))
        assertTrue(s.matched)
        val rows = s.rows.associateBy { it.week }
        assertEquals((1..18).toList(), rows.keys.sorted())
        assertEquals("@ Sea", rows.getValue(1).opponent)
        assertEquals("bye", rows.getValue(11).status)
        assertEquals(45.96, rows.getValue(5).points)
        assertEquals("live", rows.getValue(6).status)
        assertNotNull(rows.getValue(7).projected)
        assertEquals("yahoo", rows.getValue(7).projectedSource)
        assertEquals(18, fake.seeds())
        assertTrue(saved > 0)
    }

    @Test
    fun `a second opening fetches nothing`() {
        val fake = Fake()
        val h = history(fake)
        season(h)
        val before = fake.calls.size
        season(h)
        assertEquals(before, fake.calls.size)
    }

    @Test
    fun `finished weeks survive a restart and open ones go stale`() {
        val first = history(Fake())
        season(first)
        val stored = first.toJson()
        assertEquals(setOf("1", "2", "3", "4", "5"), stored.getValue("projections").jsonObject.keys)

        val fake = Fake()
        val restarted = history(fake)
        restarted.load(History.parse(stored.toString()).jsonObject)
        season(restarted)
        assertEquals(13, fake.seeds())
        season(restarted, at = now + PROJECTION_TTL_SECONDS + 1)
        assertEquals(26, fake.seeds())
    }

    @Test
    fun `another league's store is ignored`() {
        val h = history(Fake())
        h.load(buildJsonObject { put("league_id", "1"); put("season", 2026) })
        assertEquals(0, h.toJson().getValue("projections").jsonObject.size)
    }

    @Test
    fun `without ESPN the projections and schedule still show`() {
        val s = season(history(Fake(fail = listOf(History.espnPlayersUrl(2026)))))
        assertFalse(s.matched)
        val one = s.rows.first { it.week == 1 }
        assertNull(one.points)
        assertNotNull(one.projected)
    }

    @Test
    fun `a week without Yahoo's projection takes ESPN's`() {
        val s = season(history(Fake(fail = listOf(History.seedWeekUrl(league, 9)))))
        val nine = s.rows.first { it.week == 9 }
        assertEquals("espn", nine.projectedSource)
        assertTrue(nine.projected!! > 0)
        assertEquals("yahoo", s.rows.first { it.week == 10 }.projectedSource)
    }

    @Test
    fun `with neither projection the week is blank`() {
        val s = season(history(Fake(fail = listOf(History.seedWeekUrl(league, 9), History.espnPlayersUrl(2026)))))
        val nine = s.rows.first { it.week == 9 }
        assertNull(nine.projected)
        assertNull(nine.projectedSource)
        assertNotNull(s.rows.first { it.week == 10 }.projected)
    }

    private fun weekSeed(fake: Fake, week: Int) = runBlocking { History.parse(fake.fetch(History.seedWeekUrl(league, week))).jsonObject }

    @Test
    fun `the live poll learns the week's byes from the schedule`() {
        val fake = Fake()
        val h = history(fake)
        val seed = weekSeed(fake, 11)
        assertTrue("17" in runBlocking { h.byes(seed, now) }) // New England
        runBlocking { h.byes(seed, now + 10) } // kept a day: the next poll asks nobody
        assertEquals(1, fake.calls.count { it == History.espnScheduleUrl(2026) })
    }

    @Test
    fun `no schedule, no byes, and ESPN is not asked every poll`() {
        val fake = Fake(fail = listOf(History.espnScheduleUrl(2026)))
        val h = history(fake)
        val seed = weekSeed(fake, 11)
        assertEquals(emptySet<String>(), runBlocking { h.byes(seed, now) })
        assertEquals(emptySet<String>(), runBlocking { h.byes(seed, now + 10) })
        assertEquals(1, fake.calls.count { it == History.espnScheduleUrl(2026) })
    }

    @Test
    fun `no schedule, no season`() {
        assertThrows<Exception> { season(history(Fake(fail = listOf(History.espnScheduleUrl(2026))))) }
    }

    @Test
    fun `a player on no roster is said so`() {
        assertThrows<PlayerNotRostered> { season(history(Fake()), player = "1") }
    }
}
