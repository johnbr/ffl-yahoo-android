package io.github.johnbr.fflyahoo.core.client

import io.github.johnbr.fflyahoo.core.FetchFailed
import io.github.johnbr.fflyahoo.core.Golden
import io.github.johnbr.fflyahoo.core.LeagueData
import io.github.johnbr.fflyahoo.core.STALE_AFTER_SECONDS
import io.github.johnbr.fflyahoo.core.seed.Seed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** Port of `tests/test_redzone_client.py`, against the relay/seed split. */
class ClientTest {
    private val league = "999999"

    private fun bodies() = mutableMapOf(
        "redzone" to Golden.fixture("yahoo_redzone_2026_w1.json"),
        "stats" to Golden.fixture("yahoo_relay_stats_2026_w1.txt"),
        "games" to Golden.fixture("yahoo_relay_games_2026_w1.txt"),
    )

    /** Answers by the first key found in the URL; [fail] keys throw, like a dropped request. */
    private class FakeFetch(var bodies: Map<String, String>, var fail: Set<String> = emptySet()) : Fetcher {
        val log = ArrayList<String>()
        override suspend fun fetch(url: String): String {
            log += url
            for ((key, body) in bodies) {
                if (key in url) {
                    if (key in fail) throw IOException("boom on $key")
                    return body
                }
            }
            throw IOException("unexpected url $url")
        }
    }

    private fun source(fetch: Fetcher, leagueId: String = league, conditional: ConditionalFetcher? = null) =
        LeagueSource(SeedClient(fetch, leagueId), RelayClient(fetch, conditional))

    private fun scores(data: LeagueData) = data.standings.flatMap { it.toList() }.associate { it.name to it.points }

    @Test
    fun `one refresh is three requests whatever the league size`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val data = source(fetch).refresh(100.0)
        assertEquals(3, fetch.log.size)
        assertEquals(5, data.standings.size)
        assertEquals(100.0, data.fetchedAt)
    }

    @Test
    fun `a missing live feed is not a failure`() = runBlocking {
        val data = source(FakeFetch(bodies(), setOf("stats", "games"))).refresh(0.0)
        assertEquals(5, data.standings.size)
        assertTrue(data.standings.flatMap { it.toList() }.all { it.points == 0.0 })
    }

    @Test
    fun `a dropped stat feed does not score the league zero`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val src = source(fetch)
        val scored = scores(src.refresh(100.0))
        assertTrue(scored.values.any { it != 0.0 })
        fetch.fail = setOf("stats")
        val held = src.refresh(130.0)
        assertEquals(scored, scores(held))
        assertEquals(130.0, held.fetchedAt)
    }

    @Test
    fun `a held stat feed stops pretending eventually`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val src = source(fetch)
        src.refresh(100.0)
        fetch.fail = setOf("stats")
        val e = assertThrows<FetchFailed> { runBlocking { src.refresh(100.0 + STALE_AFTER_SECONDS + 1) } }
        assertTrue("no stats feed" in e.message!!)
    }

    @Test
    fun `a recovered stat feed is preferred to the copy in hand`() = runBlocking {
        val b = bodies()
        val fetch = FakeFetch(b)
        val src = source(fetch)
        src.refresh(100.0)
        fetch.fail = setOf("stats")
        src.refresh(130.0)

        val moved = b.getValue("stats").replaceFirst("r|40881|5|36|0|16|0|0|3", "r|40881|5|136|0|16|0|0|3")
        assertTrue(moved != b["stats"])
        fetch.bodies = b + ("stats" to moved)
        fetch.fail = emptySet()
        val recovered = src.refresh(160.0)
        assertEquals(22.74, scores(recovered).getValue("Blitz Brigade")!!, 1e-9)

        fetch.fail = setOf("stats")
        val later = src.refresh(160.0 + STALE_AFTER_SECONDS - 1)
        assertEquals(scores(recovered), scores(later))
    }

    @Test
    fun `a dropped games feed does not erase the slate`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val src = source(fetch)
        val good = src.refresh(100.0)
        assertEquals(1, good.activeGames)
        fetch.fail = setOf("games")
        val held = src.refresh(130.0)
        assertEquals(good.nflGames.size, held.nflGames.size)
        assertEquals(good.activeGames, held.activeGames)
        assertEquals(good.liveTick, held.liveTick)
        assertEquals(good.playsFeeds, held.playsFeeds)
    }

    @Test
    fun `the games feed is held on a shorter leash than the stats feed`() = runBlocking {
        assertTrue(FEED_HOLD_SECONDS.getValue("games") < FEED_HOLD_SECONDS.getValue("stats"))
        val fetch = FakeFetch(bodies())
        val src = source(fetch)
        src.refresh(100.0)
        fetch.fail = setOf("games")
        assertEquals(1, src.refresh(100.0 + FEED_HOLD_SECONDS.getValue("games") - 1).activeGames)
        val e = assertThrows<FetchFailed> { runBlocking { src.refresh(100.0 + FEED_HOLD_SECONDS.getValue("games") + 1) } }
        assertTrue("no games feed" in e.message!!)
    }

    @Test
    fun `a feed that never arrived is not held`() = runBlocking {
        val data = source(FakeFetch(bodies(), setOf("stats", "games"))).refresh(0.0)
        assertTrue(data.nflGames.isEmpty())
        assertEquals(0, data.activeGames)
    }

    @Test
    fun `one feed dropping does not hold the other`() = runBlocking {
        val b = bodies()
        val fetch = FakeFetch(b)
        val src = source(fetch)
        src.refresh(100.0)
        val moved = b.getValue("games").replaceFirst("|14:52|", "|11:07|")
        assertTrue(moved != b["games"])
        fetch.bodies = b + ("games" to moved)
        fetch.fail = setOf("stats")
        assertTrue("11:07" in src.refresh(130.0).liveTick)
    }

    @Test
    fun `a missing seed is a failure`() {
        assertThrows<FetchFailed> { runBlocking { source(FakeFetch(bodies(), setOf("redzone"))).refresh(0.0) } }
    }

    @Test
    fun `a truncated response is rejected`() {
        val e = assertThrows<FetchFailed> { runBlocking { source(FakeFetch(mapOf("redzone" to "{}"))).refresh(0.0) } }
        assertTrue("suspiciously short" in e.message!!)
    }

    @Test
    fun `a wrong league id says so`() {
        val e = assertThrows<FetchFailed> { runBlocking { source(FakeFetch(bodies()), "123456").refresh(0.0) } }
        assertTrue("could not read league" in e.message!!)
    }

    @Test
    fun `stale data beats an empty card`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val src = source(fetch)
        val good = src.refresh(100.0)
        fetch.fail = setOf("redzone")
        val served = src.refreshOrStale(100.0 + SEED_TTL_SECONDS + 1)
        assertSame(good, served)
        assertEquals(100.0, served.fetchedAt)
    }

    @Test
    fun `the seed is not refetched on every poll`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val src = source(fetch)
        src.refresh(100.0)
        assertEquals(1, fetch.log.count { "redzone" in it })
        fetch.log.clear()
        src.refresh(100.0 + SEED_TTL_SECONDS - 1)
        assertEquals(0, fetch.log.count { "redzone" in it })
        assertEquals(2, fetch.log.size)
        fetch.log.clear()
        src.refresh(100.0 + SEED_TTL_SECONDS + 1)
        assertEquals(1, fetch.log.count { "redzone" in it })
    }

    @Test
    fun `a seed that cannot be read is not cached`() = runBlocking {
        val fetch = FakeFetch(bodies() + ("redzone" to """{"service": {"leagues": {}}}                   """))
        val src = source(fetch)
        assertThrows<FetchFailed> { runBlocking { src.refresh(100.0) } }
        fetch.log.clear()
        assertThrows<FetchFailed> { runBlocking { src.refresh(110.0) } }
        assertEquals(1, fetch.log.count { "redzone" in it }, "a bad seed is fetched again, not served from cache")
    }

    @Test
    fun `stale data stops pretending eventually`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val src = source(fetch)
        src.refresh(100.0)
        fetch.fail = setOf("redzone")
        val e = assertThrows<FetchFailed> { runBlocking { src.refreshOrStale(100.0 + STALE_AFTER_SECONDS + 1) } }
        assertTrue("no fresh data" in e.message!!)
    }

    @Test
    fun `first ever refresh has nothing to fall back on`() {
        assertThrows<FetchFailed> { runBlocking { source(FakeFetch(bodies(), setOf("redzone"))).refreshOrStale(0.0) } }
    }

    @Test
    fun `team choices for adding a league`() = runBlocking {
        val seed = SeedClient(FakeFetch(bodies()), league).fetchSeed()
        val teams = Seed.teamChoices(seed, league)
        assertEquals(10, teams.size)
        assertTrue(teams.values.all { it.isNotEmpty() })
        assertEquals("Test League", Seed.leagueName(seed, league))
    }

    // -- the play feed: conditional, cached, asked for on every poll ---------------------------------

    private val playsV1 = "# nfl/plays-2.txt\np|2|1|1|10|65|8|1|15:00|2|9|[1] passed to [2] for 9 yard gain\n"
    private val playsV2 = playsV1 + "p|2|2|2|1|56|8|1|14:30|1|1|[3] rushed for a 1 yard touchdown\n"

    /** A play feed that honours `If-Modified-Since` the way Yahoo's does. */
    private class FakeRelay(var body: String, var modified: String = "Thu, 17 Sep 2026 02:14:19 GMT") : ConditionalFetcher {
        val asks = ArrayList<String?>()
        var fail = false
        override suspend fun fetch(url: String, since: String?): Pair<String?, String> {
            asks += since
            if (fail) throw IOException("boom")
            if (since != null && since == modified) return null to since
            return body to modified
        }
    }

    private fun relay(fake: FakeRelay) = RelayClient(FakeFetch(bodies()), fake)

    @Test
    fun `an unchanged play feed is a 304 and the cached body`() = runBlocking {
        val fake = FakeRelay(playsV1)
        val client = relay(fake)
        assertEquals(playsV1, client.plays("2", 100.0))
        assertEquals(listOf<String?>(null), fake.asks)
        assertEquals(playsV1, client.plays("2", 110.0))
        assertEquals(fake.modified, fake.asks.last())
    }

    @Test
    fun `a changed play feed arrives on the next poll`() = runBlocking {
        val fake = FakeRelay(playsV1)
        val client = relay(fake)
        client.plays("2", 100.0)
        fake.body = playsV2
        fake.modified = "Thu, 17 Sep 2026 02:14:49 GMT"
        assertEquals(playsV2, client.plays("2", 110.0))
        assertEquals(playsV2, client.plays("2", 120.0))
        assertEquals("Thu, 17 Sep 2026 02:14:49 GMT", fake.asks.last())
    }

    @Test
    fun `two asks in one poll are one request`() = runBlocking {
        val fake = FakeRelay(playsV1)
        val client = relay(fake)
        client.plays("2", 100.0)
        client.plays("2", 100.0)
        client.plays("2", 100.0 + PLAYS_MIN_INTERVAL_SECONDS - 1)
        assertEquals(1, fake.asks.size)
        client.plays("2", 100.0 + PLAYS_MIN_INTERVAL_SECONDS)
        assertEquals(2, fake.asks.size)
    }

    @Test
    fun `a 304 is not trusted forever`() = runBlocking {
        val fake = FakeRelay(playsV1)
        val client = relay(fake)
        client.plays("2", 100.0)
        client.plays("2", 100.0 + PLAYS_RECHECK_SECONDS / 2)
        assertTrue(fake.asks.last() != null)
        client.plays("2", 100.0 + PLAYS_RECHECK_SECONDS + 1)
        assertEquals(null, fake.asks.last())
        client.plays("2", 100.0 + PLAYS_RECHECK_SECONDS + 20)
        assertTrue(fake.asks.last() != null)
    }

    @Test
    fun `a failed play fetch serves what it had`() = runBlocking {
        val fake = FakeRelay(playsV1)
        val client = relay(fake)
        fake.fail = true
        assertEquals("", client.plays("2", 100.0))
        fake.fail = false
        client.plays("2", 110.0)
        fake.fail = true
        assertEquals(playsV1, client.plays("2", 120.0))
    }

    @Test
    fun `play feeds are per game`() = runBlocking {
        val fake = FakeRelay(playsV1)
        val client = relay(fake)
        client.plays("2", 100.0)
        client.plays("7", 100.0)
        assertEquals(listOf<String?>(null, null), fake.asks)
    }

    @Test
    fun `without a conditional fetcher the feed is fetched whole`() = runBlocking {
        val fetch = FakeFetch(bodies() + ("plays-2" to playsV1))
        val client = RelayClient(fetch)
        assertEquals(playsV1, client.plays("2", 100.0))
        assertEquals(playsV1, client.plays("2", 100.0))
        assertEquals(2, fetch.log.count { "plays-2" in it })
    }

    // -- the player dictionary: cached by age, refetched when it is missing a name -------------------

    private val playersV1 = "# nfl/players.txt\nm|1|8|QB|Jared|Goff|-|16\n"
    private val playersV2 = playersV1 + "m|2|8|RB|Jahmyr|Gibbs|-|26\n"

    private class Dictionary(var body: String) : Fetcher {
        var asks = 0
        override suspend fun fetch(url: String): String {
            if ("players" in url) {
                asks++
                yield() // a real fetch yields; concurrent askers interleave here
                return body
            }
            throw IOException("unexpected url $url")
        }
    }

    @Test
    fun `a name the dictionary lacks refetches it`() = runBlocking {
        val feed = Dictionary(playersV1)
        val client = RelayClient(feed)
        assertEquals(mapOf("1" to "Jared Goff"), client.players(100.0, listOf("1")))
        assertEquals(1, feed.asks)
        feed.body = playersV2
        assertEquals(mapOf("1" to "Jared Goff", "2" to "Jahmyr Gibbs"), client.players(100.0 + PLAYERS_RETRY_SECONDS, listOf("1", "2")))
        assertEquals(2, feed.asks)
    }

    @Test
    fun `a refetch for a missing name is floored`() = runBlocking {
        val feed = Dictionary(playersV1)
        val client = RelayClient(feed)
        client.players(100.0)
        for (tick in 1..3) client.players(100.0 + tick * PLAYERS_RETRY_SECONDS / 4, listOf("99"))
        assertEquals(1, feed.asks)
        client.players(100.0 + PLAYERS_RETRY_SECONDS, listOf("99"))
        assertEquals(2, feed.asks)
        client.players(100.0 + PLAYERS_RETRY_SECONDS + 10, listOf("99"))
        assertEquals(2, feed.asks)
    }

    @Test
    fun `a dictionary that knows every name is kept by age`() = runBlocking {
        val feed = Dictionary(playersV1)
        val client = RelayClient(feed)
        val first = client.players(100.0)
        for (tick in 1 until 20) assertSame(first, client.players(100.0 + tick * PLAYERS_RETRY_SECONDS, listOf("1")))
        assertEquals(1, feed.asks)
        client.players(100.0 + PLAYERS_TTL_SECONDS, listOf("1"))
        assertEquals(2, feed.asks)
    }

    @Test
    fun `a failed dictionary refetch keeps the copy in hand`() = runBlocking {
        val feed = Dictionary(playersV1)
        val client = RelayClient(feed)
        val first = client.players(100.0)
        feed.body = ""
        assertSame(first, client.players(100.0 + PLAYERS_RETRY_SECONDS, listOf("2")))
    }

    @Test
    fun `eight games asking at once is one fetch`() = runBlocking {
        val feed = Dictionary(playersV1)
        val client = RelayClient(feed)
        client.players(100.0)
        feed.body = playersV2
        val got = (1..8).map { async { client.players(100.0 + PLAYERS_RETRY_SECONDS, listOf("2")) } }.awaitAll()
        assertEquals(2, feed.asks)
        assertTrue(got.all { it === got[0] })
    }

    // -- not in the Python: leagues share the live feeds ---------------------------------------------

    @Test
    fun `two leagues polling together share one fetch of the live feeds`() = runBlocking {
        val fetch = FakeFetch(bodies())
        val relay = RelayClient(fetch)
        val first = LeagueSource(SeedClient(fetch, league), relay)
        val second = LeagueSource(SeedClient(fetch, league), relay)
        first.refresh(100.0)
        second.refresh(101.0)
        assertEquals(1, fetch.log.count { it.endsWith("/stats.txt") })
        second.refresh(100.0 + LIVE_SHARE_SECONDS)
        assertEquals(2, fetch.log.count { it.endsWith("/stats.txt") })
    }
}
