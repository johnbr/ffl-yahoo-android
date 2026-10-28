package io.github.johnbr.fflyahoo.core.client

import io.github.johnbr.fflyahoo.core.FetchFailed
import io.github.johnbr.fflyahoo.core.LeagueData
import io.github.johnbr.fflyahoo.core.STALE_AFTER_SECONDS
import io.github.johnbr.fflyahoo.core.YahooError
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.seed.Seed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** `(url) -> body`. Throws on any transport error or non-2xx status. */
fun interface Fetcher {
    suspend fun fetch(url: String): String
}

/** `(url, ifModifiedSince) -> (body, lastModified)`, with a null body for `304 Not Modified`. */
fun interface ConditionalFetcher {
    suspend fun fetch(url: String, since: String?): Pair<String?, String>
}

/**
 * The User-Agent every request carries. The endpoints answer without one; it is sent so the traffic is
 * identifiable, not to evade anything. If Yahoo starts refusing, the answer is to fetch less.
 */
const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

/** Anything under this is a truncated response or an error page, not data. */
const val MIN_BODY = 40

/** How long a live feed's last good copy stands in after a dropped fetch: stats 15 min, games 2. */
val FEED_HOLD_SECONDS = mapOf("stats" to STALE_AFTER_SECONDS, "games" to 120.0)

/** How long the 180 KB league seed is reused between refreshes. */
const val SEED_TTL_SECONDS = 180.0

/**
 * How long past its TTL an aged-out seed still serves while a fresh one is fetched in the background
 * ([SeedClient.refresh]): the live cadence's next poll finds the new one. A poll later than this fetches
 * it in line, as before, so a seed never goes stale for a whole near-game or idle interval.
 */
const val SEED_GRACE_SECONDS = 60.0

/** How long the NFL player dictionary is reused when nothing says it has grown. */
const val PLAYERS_TTL_SECONDS = 1800.0

/** The floor between two refetches of the dictionary prompted by an id it lacks. */
const val PLAYERS_RETRY_SECONDS = 30.0

/** How often a play feed is fetched whole regardless of `If-Modified-Since`. */
const val PLAYS_RECHECK_SECONDS = 90.0

/** The floor between two asks for the same play feed — a second ask in one poll shares the first. */
const val PLAYS_MIN_INTERVAL_SECONDS = 2.0

/**
 * The window in which two leagues' polls share one fetch of the live feeds. Not in the Python, which
 * follows one league per client: here every league reads the same `stats.txt` and `games.txt`.
 */
const val LIVE_SHARE_SECONDS = 2.0

/** Run [block], turning any non-cancellation failure into a [Result]. */
internal suspend inline fun <T> attempt(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

internal suspend fun get(fetch: Fetcher, url: String): String {
    val body = try {
        fetch.fetch(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: YahooError) {
        throw e
    } catch (e: Exception) {
        throw FetchFailed("fetching $url: ${e.message}", e)
    }
    if (body.length < MIN_BODY) throw FetchFailed("suspiciously short response from $url (${body.length}B)")
    return body
}

/**
 * Yahoo's live relay feeds — the same for every league, so one instance serves them all. Port of the
 * relay half of `redzone_client.RedzoneClient`. [now] is always supplied by the caller: nothing here
 * reads a clock, which keeps it deterministic under test.
 */
class RelayClient(
    private val fetch: Fetcher,
    private val fetchIfModified: ConditionalFetcher? = null,
    private val sport: String = "nfl",
) {
    private class PlaysCache(val body: String, val lastModified: String, var fetchedAt: Double, val loadedAt: Double)

    private val live = HashMap<String, Pair<String, Double>>()
    private val liveLock = Mutex()
    private var shared: Pair<Double, Result<Pair<String, String>>>? = null

    private val playersLock = Mutex()
    private var players: Map<String, String>? = null
    private var playersAt = 0.0

    private val playsLock = Mutex()
    private val plays = HashMap<String, PlaysCache>()

    /** A live feed that may legitimately be missing (between seasons, before the week's first game). */
    private suspend fun getOptional(url: String): String = attempt { get(fetch, url) }.getOrDefault("")

    /**
     * One live feed, holding its last good copy over a failed fetch. A feed Yahoo serves empty still
     * carries its comment header, so `""` here means the fetch failed. Past the feed's hold it raises,
     * handing the poll to [LeagueSource.refreshOrStale].
     */
    private fun liveFeed(feed: String, body: String, now: Double): String {
        if (body.isNotEmpty()) {
            live[feed] = body to now
            return body
        }
        val (last, arrivedAt) = live[feed] ?: return "" // nothing has ever arrived: empty is the truth
        val age = now - arrivedAt
        if (age > FEED_HOLD_SECONDS.getValue(feed)) throw FetchFailed("no $feed feed for ${age.toLong()}s")
        return last
    }

    /** `(stats, games)` for one poll, fetched concurrently and shared by polls inside [LIVE_SHARE_SECONDS]. */
    suspend fun liveFeeds(now: Double): Pair<String, String> = liveLock.withLock {
        shared?.let { (at, result) -> if (now - at in 0.0..<LIVE_SHARE_SECONDS) return@withLock result.getOrThrow() }
        val result = attempt {
            val (stats, games) = coroutineScope {
                val stats = async { getOptional(Relay.relayUrl("stats", sport)) }
                val games = async { getOptional(Relay.relayUrl("games", sport)) }
                stats.await() to games.await()
            }
            liveFeed("stats", stats, now) to liveFeed("games", games, now)
        }
        shared = now to result
        result.getOrThrow()
    }

    /**
     * `{player_id: name}` for everyone who has played this week. An id in [needed] the cached copy lacks
     * means the dictionary has grown, and it is refetched — no sooner than [PLAYERS_RETRY_SECONDS] after
     * the last read. The same map object comes back until a refetch replaces it.
     */
    suspend fun players(now: Double, needed: Collection<String> = emptyList()): Map<String, String> = playersLock.withLock {
        val cached = players
        if (cached != null) {
            val age = now - playersAt
            val lacking = needed.any { it !in cached }
            if (age < PLAYERS_TTL_SECONDS && !(lacking && age >= PLAYERS_RETRY_SECONDS)) return@withLock cached
        }
        val body = getOptional(Relay.relayUrl("players", sport))
        if (body.isEmpty()) return@withLock cached ?: emptyMap()
        Relay.parsePlayers(body).also {
            players = it
            playersAt = now
        }
    }

    /**
     * One game's play-by-play feed, or `""`. Served from cache only when the server says `304`, or when
     * asked twice inside [PLAYS_MIN_INTERVAL_SECONDS]; forced whole every [PLAYS_RECHECK_SECONDS].
     */
    suspend fun plays(playsId: String, now: Double): String {
        if (playsId.isEmpty()) return ""
        val url = Relay.relayUrl("plays-$playsId", sport)
        val conditional = fetchIfModified ?: return getOptional(url)

        val cached = playsLock.withLock { plays[playsId] }
        if (cached != null && now - cached.fetchedAt < PLAYS_MIN_INTERVAL_SECONDS) return cached.body

        val since = if (cached != null && now - cached.loadedAt < PLAYS_RECHECK_SECONDS) cached.lastModified.ifEmpty { null } else null
        val (body, lastModified) = attempt { conditional.fetch(url, since) }.getOrElse { return cached?.body ?: "" }
        return playsLock.withLock {
            when {
                body == null -> {
                    // 304: what we have is what there is.
                    if (cached == null) return@withLock ""
                    cached.fetchedAt = now
                    cached.body
                }
                body.length < MIN_BODY -> cached?.body ?: ""
                else -> {
                    plays[playsId] = PlaysCache(body, lastModified, now, now)
                    body
                }
            }
        }
    }
}

/** One league's seed, cached for [SEED_TTL_SECONDS]. */
class SeedClient(private val fetch: Fetcher, val leagueId: String, private val sport: String = "nfl", private val ttl: Double = SEED_TTL_SECONDS) {
    private val lock = Mutex()
    private var seed: JsonObject? = null
    private var seedAt = 0.0

    /** The seed on its own, uncached — used when adding a league to validate it. */
    suspend fun fetchSeed(): JsonObject {
        val body = get(fetch, Relay.redzoneUrl(leagueId, sport))
        return try {
            Seed.parse(body)
        } catch (e: Exception) {
            throw FetchFailed("league $leagueId seed is not JSON: ${e.message}", e)
        }
    }

    /** The seed, fetched in line only once it is past its TTL and [SEED_GRACE_SECONDS]. */
    suspend fun payload(now: Double): JsonObject = lock.withLock {
        seed?.let { if (now - seedAt < ttl + SEED_GRACE_SECONDS) return@withLock it }
        fetchSeed().also {
            seed = it
            seedAt = now
        }
    }

    /** Whether a cached seed has aged out and wants refetching. */
    fun due(now: Double): Boolean = seed != null && now - seedAt >= ttl

    /** Refetch the seed off the poll's path. A failure keeps the old one; the next poll tries again. */
    suspend fun refresh(now: Double) {
        val fresh = attempt { fetchSeed() }.getOrNull() ?: return
        lock.withLock {
            seed = fresh
            seedAt = now
        }
    }

    /** A seed that will not parse into a league is worse than no cache: drop it. */
    suspend fun invalidate() = lock.withLock { seed = null }
}

/** One league's refresh: its seed plus the shared live feeds. Port of `RedzoneClient.async_refresh*`. */
class LeagueSource(
    val seed: SeedClient,
    val relay: RelayClient,
    /** The clubs on a bye this week, from the seed — Yahoo's feeds do not say. */
    private val byes: (suspend (JsonObject, Double) -> Set<String>)? = null,
) {
    val leagueId: String get() = seed.leagueId

    var lastGood: LeagueData? = null
        private set

    suspend fun refresh(now: Double): LeagueData {
        val (payload, feeds) = coroutineScope {
            val payload = async { seed.payload(now) }
            val feeds = async { relay.liveFeeds(now) }
            payload.await() to feeds.await()
        }
        val bye = byes?.let { find ->
            try {
                find(payload, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a missing bye line is no reason to fail a poll
                emptySet()
            }
        } ?: emptySet()
        val data = try {
            Seed.leagueFromPayloads(payload, feeds.first, feeds.second, leagueId, now, bye)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            seed.invalidate()
            throw FetchFailed("could not read league $leagueId: ${e.message}", e)
        }
        if (data.standings.isEmpty()) throw FetchFailed("no matchups in league $leagueId's week ${data.week}")
        lastGood = data
        return data
    }

    /** Refresh, falling back to the last good payload on a transient failure — up to [STALE_AFTER_SECONDS]. */
    suspend fun refreshOrStale(now: Double): LeagueData =
        try {
            refresh(now)
        } catch (e: YahooError) {
            val stale = lastGood ?: throw e
            val age = now - stale.fetchedAt
            if (age > STALE_AFTER_SECONDS) throw FetchFailed("no fresh data for ${age.toLong()}s: ${e.message}", e)
            stale
        }
}
