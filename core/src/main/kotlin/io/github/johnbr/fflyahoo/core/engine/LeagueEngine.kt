package io.github.johnbr.fflyahoo.core.engine

import io.github.johnbr.fflyahoo.core.LeagueData
import io.github.johnbr.fflyahoo.core.STALE_AFTER_SECONDS
import io.github.johnbr.fflyahoo.core.YahooError
import io.github.johnbr.fflyahoo.core.client.LeagueSource
import io.github.johnbr.fflyahoo.core.client.attempt
import io.github.johnbr.fflyahoo.core.live.Landed
import io.github.johnbr.fflyahoo.core.live.Pending
import io.github.johnbr.fflyahoo.core.live.PendingPlays
import io.github.johnbr.fflyahoo.core.live.ScoreBanners
import io.github.johnbr.fflyahoo.core.live.Situation
import io.github.johnbr.fflyahoo.core.plays.LeagueSnapshot
import io.github.johnbr.fflyahoo.core.plays.PlayFeed
import io.github.johnbr.fflyahoo.core.plays.Plays
import io.github.johnbr.fflyahoo.core.plays.ScoringEvent
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.relay.RelayPlay
import io.github.johnbr.fflyahoo.core.seed.Seed
import io.github.johnbr.fflyahoo.core.state.LeagueState
import io.github.johnbr.fflyahoo.core.state.MatchupDetail
import io.github.johnbr.fflyahoo.core.state.MyTeam
import io.github.johnbr.fflyahoo.core.state.NflGames
import io.github.johnbr.fflyahoo.core.state.NflPlayRow
import io.github.johnbr.fflyahoo.core.state.PlayRow
import io.github.johnbr.fflyahoo.core.state.Scoreboard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where a league's scoring history is kept between runs. */
interface HistoryStore {
    suspend fun load(): String?
    suspend fun save(json: String)
}

/** Everything a league's screens show, as of the last poll. */
data class LeagueUi(
    val leagueId: String,
    val scoreboard: Scoreboard,
    val nfl: NflGames,
    val myTeam: MyTeam?,
    val teamId: String?,
    /** Epoch seconds of the data shown, or null before the first successful poll. */
    val updatedAt: Double?,
    /** The last refresh's failure, cleared by the next success. */
    val error: String?,
    /** Seconds until the next scheduled poll. */
    val nextPollSeconds: Int?,
)

/**
 * A scoring event as published, with the row a screen or notification shows for it. [revised] marks a
 * re-emission of an event already published whose description changed — Yahoo's play text landing, or
 * being reworded — so a notification can be updated in place rather than raised again.
 */
data class EngineEvent(val event: ScoringEvent, val row: PlayRow, val revised: Boolean = false)

/**
 * Poll one league on an adaptive cadence and derive scoring plays. Port of `coordinator.py`.
 *
 * Every method runs on [dispatcher], which must not run two tasks at once (the default is a
 * single-parallelism view of `Dispatchers.Default`). That gives the engine the interleaving model of the
 * asyncio event loop the Python was written for: suspensions interleave, nothing runs in parallel.
 */
class LeagueEngine(
    private val source: LeagueSource,
    private val history: HistoryStore,
    private val clock: () -> Double,
    private val scope: CoroutineScope,
    val leagueName: String = "",
    val teamId: String? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** Re-read awaited play feeds between polls. Off only in tests that replay polls one by one. */
    private val chaseBetweenPolls: Boolean = true,
) {
    val leagueId: String get() = source.leagueId

    var feed = PlayFeed()
        private set
    private var previous: LeagueSnapshot? = null
    private var interval: Int? = null
    var data: LeagueData? = null
        private set
    private var lastError: String? = null

    /** The games screen's play lists, re-rendered only when what they were rendered from changed. */
    private val nflPlays = HashMap<String, GamePlays>()

    /** `{plays_id: newest play text}` for live games, refreshed every poll. */
    private var nflLastPlays: Map<String, String> = emptyMap()

    /** `{plays_id: newest play sequence}` as of the END of the last poll — the scoring matcher's floor. */
    private val playsFloor = HashMap<String, Int>()
    private val pending = PendingPlays()
    private val banners = ScoreBanners()

    /** The poll and the chase between polls both write the last-play lines; one at a time. */
    private val nflLock = Mutex()
    private var chase: Job? = null

    private val _state = MutableStateFlow(snapshotUi())
    val state: StateFlow<LeagueUi> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)

    /** Each new scoring play, and each later piece folded into one (same `eventId`, running total). */
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Restore the play feed so a mid-Sunday restart keeps its history. Best-effort. */
    suspend fun loadHistory() = withContext(dispatcher) {
        attempt { history.load() }.getOrNull()?.let { feed = PlayFeed.fromJson(it) }
        publish()
    }

    /** Poll forever on the adaptive cadence; [refreshNow] cuts the wait short. */
    suspend fun run() {
        while (true) {
            val next = refreshSafely()
            withTimeoutOrNull(next * 1000L) { wake.receive() }
        }
    }

    /** Ask the polling loop to refresh now (pull-to-refresh, the app coming to the foreground). */
    fun refreshNow() {
        wake.trySend(Unit)
    }

    /** One refresh, never throwing: returns the seconds until the next. */
    suspend fun refreshSafely(): Int =
        try {
            refresh()
        } catch (e: YahooError) {
            withContext(dispatcher) {
                lastError = e.message
                publish()
            }
            interval ?: RETRY_SECONDS
        }

    /** One poll. Throws [YahooError] when there is nothing fit to show; the interval is then unchanged. */
    suspend fun refresh(): Int = withContext(dispatcher) {
        val now = clock()
        val fresh = source.refreshOrStale(now)
        processPlays(fresh, now)
        observeBanners(fresh, now)
        refreshNflLastPlays(fresh, now)
        data = fresh
        lastError = null
        startChase(now)
        val next = LeagueState.pollInterval(fresh, interval)
        interval = next
        publish()
        next
    }

    private fun snapshotUi(): LeagueUi {
        val now = clock()
        return LeagueUi(
            leagueId = leagueId,
            scoreboard = LeagueState.scoreboard(data, feed, leagueId, leagueName),
            nfl = LeagueState.nflGames(data, leagueId, nflLastPlays, pending.texts(), banners.active(now)),
            myTeam = LeagueState.findTeam(data, teamId),
            teamId = teamId,
            updatedAt = data?.fetchedAt,
            error = lastError,
            nextPollSeconds = interval,
        )
    }

    private fun publish() {
        _state.value = snapshotUi()
    }

    private fun observeBanners(data: LeagueData, now: Double) {
        val floors = data.nflGames.mapNotNull { g -> playsFloor[g.playsId]?.let { g.gameId to it } }.toMap()
        banners.observe(data.nflGames, now, floors)
    }

    /**
     * Newest play text for each LIVE NFL game, every poll — the play feed is the only way to know a
     * play's text has landed. [only] narrows it to those games, between polls. Returns whether any line
     * changed. Every failure is swallowed: a missing line is cosmetic.
     */
    private suspend fun refreshNflLastPlays(data: LeagueData, now: Double, only: List<String>? = null): Boolean {
        val games = data.nflGames.filter { it.state == "in" && it.playsId.isNotEmpty() }.associateBy { it.playsId }
        val live = only?.filter { it in games } ?: games.keys.take(MAX_NFL_LAST_PLAY_FEEDS)

        return nflLock.withLock {
            val before = nflLastPlays to pending.texts()
            var changed = false
            if (only == null) pending.forgetOthers(live.toSet())
            val results = coroutineScope {
                live.map { playsId -> async { attempt { gamePlays(playsId, 1, now) } } }.awaitAll()
            }
            val lastPlays = if (only == null) LinkedHashMap() else LinkedHashMap(nflLastPlays)
            for ((playsId, result) in live.zip(results)) {
                var newest: Landed? = null
                val rows = result.getOrNull()
                if (rows.isNullOrEmpty()) {
                    // Keep the line we had rather than blanking it for one bad poll.
                    nflLastPlays[playsId]?.let { lastPlays[playsId] = it }
                } else {
                    lastPlays[playsId] = rows[0].shortText.ifEmpty { rows[0].text }
                    newest = landed(playsId, rows[0].playId)
                    // The floor is "as of the end of the last POLL"; the chase must not move it.
                    if (only == null && newest != null) playsFloor[playsId] = newest.sequence
                }
                val game = games.getValue(playsId)
                pending.observe(playsId, game, newest, now)
                // The play after a scoring play is what takes its banner down.
                nflPlays[playsId]?.let { cached ->
                    if (banners.plays(game.gameId, cached.plays.map { it.sequence to it.playType }, now)) changed = true
                }
            }
            nflLastPlays = lastPlays
            changed || (nflLastPlays to pending.texts()) != before
        }
    }

    /** The play on a game's screen, and the situation it ran from. */
    private fun landed(playsId: String, playId: String): Landed? {
        val sequence = playId.substringAfterLast(".").toIntOrNull() ?: return null
        val cached = nflPlays[playsId] ?: return null
        val play = cached.plays.lastOrNull { it.sequence == sequence }
            ?: return Landed(sequence, Situation("", 0, 0, 0))
        return Landed(sequence, Pending.situationOf(play), play.period, play.clock, play.playType)
    }

    /** Re-read the play feeds of games awaiting text, every few seconds, until the next poll. */
    private fun startChase(now: Double) {
        if (!chaseBetweenPolls || chase?.isActive == true) return
        if (pending.awaiting(now).isEmpty()) return
        chase = scope.launch(dispatcher) {
            while (true) {
                delay((CHASE_SECONDS * 1000).toLong())
                val t = clock()
                val current = data
                val waiting = pending.awaiting(t)
                if (current == null || waiting.isEmpty()) return@launch
                if (refreshNflLastPlays(current, t, only = waiting)) publish()
            }
        }
    }

    /** Diff against the previous poll and publish anything new. */
    private suspend fun processPlays(data: LeagueData, now: Double) {
        if (data.matchups.isEmpty()) return
        val snapshot = Seed.toSnapshot(data.matchups, data.week, now, leagueId)
        // A new week starts a new history rather than appending to the last.
        if (feed.startWeek(data.week)) previous = null
        // Not in the Python, which polls without pause: after a long gap (the app was closed, live mode
        // off) the first poll is a silent baseline, as a Home Assistant restart is, rather than an
        // hour of scoring arriving as a burst of merged events.
        previous?.let { if (now - it.takenAt > STALE_AFTER_SECONDS) previous = null }

        var events = Plays.diffSnapshots(previous, snapshot)
        previous = snapshot

        // Events already on the screen are re-matched too — that is the point of the revision window.
        val revisable = feed.recent(50, includeCorrections = true).filter { now - it.timestamp <= PLAY_REVISION_SECONDS }
        val version = feed.version
        if (events.isNotEmpty() || revisable.isNotEmpty()) events = describe(data, events, revisable, now)

        // A later piece of a play already on the feed comes back as that play's row, under the id
        // listeners already know: they see the running total, not a second event.
        for (event in feed.add(events)) _events.tryEmit(EngineEvent(event, LeagueState.playRow(event)))
        if (feed.version != version) {
            val json = feed.toJson()
            scope.launch(dispatcher) { attempt { history.save(json) } }
        }
    }

    /**
     * Attach Yahoo's own play description to new events (before they are stored, so a notification says
     * what the screen will), and refresh recent ones. Every failure is swallowed.
     */
    private suspend fun describe(data: LeagueData, events: List<ScoringEvent>, revisable: List<ScoringEvent>, now: Double): List<ScoringEvent> {
        fun feedOf(event: ScoringEvent): String? = data.playsFeeds[event.nflTeam ?: ""]?.takeIf { it.isNotEmpty() }

        val wanted = LinkedHashSet<String>()
        for (event in events + revisable) feedOf(event)?.let { wanted += it }
        if (wanted.isEmpty()) return events

        // A new event's play is usually NOT in the feed yet; it matches only above where the feed stood.
        val floored = events.map { event ->
            val floor = feedOf(event)?.let { playsFloor[it] }
            if (floor != null) event.copy(playFloor = floor) else event
        }

        val ids = wanted.take(MAX_PLAY_FEEDS)
        val bodies = coroutineScope { ids.map { id -> async { attempt { source.relay.plays(id, now) } } }.awaitAll() }
        val feeds = HashMap<String, List<RelayPlay>>()
        for ((id, body) in ids.zip(bodies)) {
            body.getOrNull()?.let { text -> attempt { Relay.parsePlays(text) }.getOrNull()?.let { feeds[id] = it } }
        }

        // Read AFTER the feeds, told every id they name: a first touch is not in an older copy.
        val names = attempt { source.relay.players(now, idsNeeded(feeds.values)) }.getOrElse { return floored }

        fun described(event: ScoringEvent): ScoringEvent {
            val plays = feeds[feedOf(event) ?: ""]
            if (plays.isNullOrEmpty()) return event
            val match = Plays.matchRelayPlay(event, plays, names, pin = event.play?.playId) ?: return event
            return if (event.play != null && event.play == match) event else event.copy(play = match)
        }

        for (event in revisable) {
            val updated = described(event)
            if (updated !== event) {
                feed.revise(event.eventId, updated.play)?.let { _events.tryEmit(EngineEvent(it, LeagueState.playRow(it), revised = true)) }
            } else if (latePiece(event, feeds[feedOf(event) ?: ""], now)) {
                feed.fold(event.eventId)
            }
        }
        return floored.map(::described)
    }

    /**
     * Recent plays for ONE NFL game, newest first. Re-rendered only when the body or the dictionary
     * changed. Returns the cached rows, or `[]`, rather than raising.
     */
    private suspend fun gamePlays(playsId: String, limit: Int, now: Double): List<NflPlayRow> {
        if (playsId.isEmpty()) return emptyList()
        val cached = nflPlays[playsId]
        val fetched = attempt {
            val body = source.relay.plays(playsId, now)
            val (plays, needed) = if (cached != null && cached.body == body) {
                cached.plays to cached.needed
            } else {
                Relay.parsePlays(body).let { it to idsNeeded(listOf(it)) }
            }
            val names = source.relay.players(now, needed)
            Triple(body, plays to needed, names)
        }.getOrElse { return cached?.rows?.take(limit) ?: emptyList() }
        val (body, parsed, names) = fetched
        val (plays, needed) = parsed
        if (cached != null && cached.body == body && cached.names === names) return cached.rows.take(limit)

        // Shortened against THIS game's ids, so two players who abbreviate alike keep their full names.
        val shortNames = Plays.abbreviateNames(names, needed)
        val rows = plays.asReversed().mapNotNull { play ->
            val text = Relay.humanizePlay(play.text, names)
            if (text.isEmpty()) {
                null
            } else {
                NflPlayRow(
                    playId = play.playId,
                    text = text,
                    shortText = Relay.humanizePlay(play.text, shortNames),
                    situation = LeagueState.downAndDistance(play.down, play.distance, play.yardsToGoal),
                    period = play.period,
                    clock = play.clock,
                )
            }
        }
        nflPlays[playsId] = GamePlays(body, plays, needed, names, rows)
        return rows.take(limit)
    }

    // -- what the screens ask for on demand (the WebSocket commands) ----------------------------------

    /** Both rosters for one matchup; [matchupIndex] is 1-based, matching Yahoo's own `mid1`. */
    suspend fun matchupDetail(matchupIndex: Int): MatchupDetail = withContext(dispatcher) {
        val current = data ?: return@withContext MatchupDetail(null, emptyList())
        LeagueState.playerRows(current, matchupIndex - 1).copy(week = current.week)
    }

    /** The week's scoring history, newest first. Deliberately no live-game filter: this is the record. */
    suspend fun playHistory(matchupId: String?, limit: Int = 100, startersOnly: Boolean = true): List<PlayRow> =
        withContext(dispatcher) {
            feed.recent(limit, matchupId = matchupId, includeCorrections = true, startersOnly = startersOnly).map(LeagueState::playRow)
        }

    /** Recent plays for one NFL game; a snap whose text is still on its way heads the list. */
    suspend fun nflPlays(playsId: String, limit: Int = 12): List<NflPlayRow> = withContext(dispatcher) {
        var plays = gamePlays(playsId, limit, clock())
        val game = data?.nflGames?.firstOrNull { it.playsId == playsId }
        val row = game?.let { pending.row(playsId, it) }
        if (row != null) plays = (listOf(row) + plays).take(limit)
        plays
    }

    private class GamePlays(
        val body: String,
        val plays: List<RelayPlay>,
        /** Every id the plays name, tacklers aside. */
        val needed: Set<String>,
        /** The dictionary object the rows were rendered with, compared by identity. */
        val names: Map<String, String>,
        val rows: List<NflPlayRow>,
    )

    companion object {
        /** How long an event keeps being re-matched against its game's play feed. */
        const val PLAY_REVISION_SECONDS = 300.0

        /** Ceiling on play feeds fetched per refresh for scoring events. */
        const val MAX_PLAY_FEEDS = 8

        /** Ceiling on last-play feeds per refresh — the whole week's slate, a runaway guard. */
        const val MAX_NFL_LAST_PLAY_FEEDS = 16

        /** How often a game's play feed is re-read between polls while a snap's text is awaited. */
        const val CHASE_SECONDS = 3.0

        /** The retry cadence when the very first refresh fails. */
        const val RETRY_SECONDS = 60

        /** Every id `humanizePlay` would look up across these games' plays. */
        fun idsNeeded(feeds: Collection<List<RelayPlay>>): Set<String> =
            feeds.flatMap { plays -> plays.flatMap { Relay.playIdsNeeded(it.text) } }.toSet()

        /**
         * Whether an event still without a play is a piece of the one before it: the text lag has passed
         * and the feed holds nothing above the floor naming the player. An unreadable feed answers no.
         */
        fun latePiece(event: ScoringEvent, plays: List<RelayPlay>?, now: Double): Boolean {
            if (event.play != null || event.correction || plays == null) return false
            if (now - event.timestamp < Plays.PLAY_TEXT_LAG_SECONDS) return false
            return !Plays.playAboveFloor(event, plays)
        }
    }
}
