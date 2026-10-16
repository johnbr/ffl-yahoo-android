package io.github.johnbr.fflyahoo.data

import android.content.Context
import io.github.johnbr.fflyahoo.core.client.LeagueSource
import io.github.johnbr.fflyahoo.core.client.RelayClient
import io.github.johnbr.fflyahoo.core.client.SeasonHistory
import io.github.johnbr.fflyahoo.core.client.SeedClient
import io.github.johnbr.fflyahoo.core.engine.LeagueEngine
import io.github.johnbr.fflyahoo.core.history.History
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One [LeagueEngine] per followed league, polling while anything needs it.
 *
 * "Anything" is a set of named owners — the visible app, the live service — so the app polls only
 * while it is open or the user has asked for live mode, and stops the moment neither holds. The relay
 * feeds are the same for every league, so every engine shares one [RelayClient].
 */
class EngineHub(
    private val context: Context,
    private val settings: SettingsStore,
    fetcher: OkHttpFetcher,
    private val scope: CoroutineScope,
) {
    private val fetch = fetcher
    private val relay = RelayClient(fetcher, fetcher)

    private data class Key(val leagueId: String, val teamId: String?)

    private val _engines = MutableStateFlow<Map<String, LeagueEngine>>(emptyMap())

    /** The engine for each followed league, by league id. */
    val engines: StateFlow<Map<String, LeagueEngine>> = _engines.asStateFlow()

    private val keys = HashMap<String, Key>()
    private val owners = MutableStateFlow<Set<String>>(emptySet())
    private val runners = HashMap<String, Job>()

    init {
        scope.launch {
            settings.settings.map { it.leagues }.distinctUntilChanged().collect(::sync)
        }
        scope.launch {
            combine(owners, _engines) { who, engines -> who.isNotEmpty() to engines }.collect { (active, engines) ->
                // Stop what should not run, start what should.
                for ((id, job) in runners.entries.toList()) {
                    if (!active || engines[id] == null) {
                        job.cancel()
                        runners.remove(id)
                    }
                }
                if (active) {
                    for ((id, engine) in engines) {
                        if (runners[id]?.isActive != true) runners[id] = scope.launch { engine.run() }
                    }
                }
            }
        }
    }

    private suspend fun sync(leagues: List<LeagueConfig>) {
        val next = LinkedHashMap<String, LeagueEngine>()
        for (league in leagues) {
            val key = Key(league.leagueId, league.teamId)
            val existing = _engines.value[league.leagueId]
            if (existing != null && keys[league.leagueId] == key) {
                next[league.leagueId] = existing
                continue
            }
            // New, or the team changed: a fresh engine (the history file carries over).
            runners.remove(league.leagueId)?.cancel()
            val engine = LeagueEngine(
                source = LeagueSource(SeedClient(fetch, league.leagueId), relay),
                history = FileHistoryStore(context, league.leagueId),
                clock = { System.currentTimeMillis() / 1000.0 },
                scope = scope,
                leagueName = league.name,
                teamId = league.teamId,
                season = seasonFor(league.leagueId),
            )
            engine.loadHistory()
            keys[league.leagueId] = key
            next[league.leagueId] = engine
        }
        for (gone in keys.keys - next.keys) {
            keys.remove(gone)
            runners.remove(gone)?.cancel()
            FileHistoryStore(context, gone).delete()
            FileSeasonStore(context, gone).delete()
        }
        _engines.value = next
    }

    /** A league's player seasons, with its finished weeks' projections read back from the file they were saved to. */
    private suspend fun seasonFor(leagueId: String): SeasonHistory {
        val store = FileSeasonStore(context, leagueId)
        lateinit var season: SeasonHistory
        // Snapshotted where SeasonHistory calls it, under its own lock; only the write is left for later.
        season = SeasonHistory(fetch, fetch, leagueId, save = {
            val json = season.toJson().toString()
            scope.launch { store.save(json) }
        })
        runCatching { store.load()?.let { season.load(History.parse(it) as? JsonObject) } }
        return season
    }

    /** Start polling on behalf of [owner] (if nothing else already is). */
    fun acquire(owner: String) = owners.update { it + owner }

    fun release(owner: String) = owners.update { it - owner }

    val active: Boolean get() = owners.value.isNotEmpty()

    /** Cut the wait short on every running engine — pull-to-refresh, coming back to the foreground. */
    fun refreshNow() = _engines.value.values.forEach { it.refreshNow() }

    /** One refresh of one league, outside the polling loop — the widget's background update. */
    suspend fun refreshOnce(leagueId: String) {
        // Called from a worker or receiver right after process start, possibly before the settings
        // have produced the engines: wait for them, briefly.
        val engine = withTimeoutOrNull(10_000) { _engines.first { leagueId in it } }?.get(leagueId) ?: return
        engine.refreshSafely()
    }
}
