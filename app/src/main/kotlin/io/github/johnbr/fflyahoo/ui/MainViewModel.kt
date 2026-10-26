package io.github.johnbr.fflyahoo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.core.YahooError
import io.github.johnbr.fflyahoo.core.client.SeedClient
import io.github.johnbr.fflyahoo.core.engine.LeagueEngine
import io.github.johnbr.fflyahoo.core.engine.LeagueUi
import io.github.johnbr.fflyahoo.core.seed.Seed
import io.github.johnbr.fflyahoo.data.AppSettings
import io.github.johnbr.fflyahoo.data.LeagueConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A league found by its id, ready for the team picker. */
data class FoundLeague(val leagueId: String, val name: String, val teams: Map<String, String>)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    private val hub = app.hub

    /** Null until DataStore has answered, so the first frame does not flash the add-league screen. */
    val settings: StateFlow<AppSettings?> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val engine: StateFlow<LeagueEngine?> =
        combine(settings, hub.engines) { s, engines -> s?.selectedLeague?.let { engines[it.leagueId] } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The selected league's screens. */
    val league: StateFlow<LeagueUi?> = engine.flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedEngine: LeagueEngine? get() = engine.value

    /** A matchup to bring up, asked for by a tap on the league widget; cleared once it is showing. */
    val openMatchup = MutableStateFlow<String?>(null)

    val liveServiceRunning: StateFlow<Boolean> = app.scheduler.running

    fun refresh() = hub.refreshNow()

    fun select(leagueId: String) = viewModelScope.launch { app.settings.select(leagueId) }

    fun remove(leagueId: String) = viewModelScope.launch { app.settings.removeLeague(leagueId) }

    /** Look a league up by id or pasted URL; returns its name and teams, or throws with a reason. */
    suspend fun find(input: String): FoundLeague {
        val id = Seed.extractLeagueId(input) ?: throw IllegalArgumentException("That doesn't look like a league id or league URL.")
        val seed = try {
            SeedClient(app.fetcher, id).fetchSeed()
        } catch (e: YahooError) {
            throw IllegalArgumentException("Couldn't reach Yahoo for league $id. Check the id and your connection.", e)
        }
        return try {
            FoundLeague(id, Seed.leagueName(seed, id), Seed.teamChoices(seed, id))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Yahoo has no league $id.", e)
        }
    }

    fun save(found: FoundLeague, teamId: String?) = viewModelScope.launch {
        app.settings.putLeague(LeagueConfig(found.leagueId, found.name, teamId, teamId?.let { found.teams[it] }))
    }

    fun setLiveMode(on: Boolean) = viewModelScope.launch {
        app.settings.setLiveMode(on)
        app.scheduler.reschedule()
    }

    fun setNotify(mine: Boolean? = null, opponent: Boolean? = null, leadChange: Boolean? = null) =
        viewModelScope.launch { app.settings.setNotify(mine, opponent, leadChange) }

    fun startLiveNow() = app.scheduler.startNow()

    fun stopLiveNow() = app.scheduler.stopNow()

    fun canScheduleExactAlarms(): Boolean = app.scheduler.canScheduleExact()

    fun ignoringBatteryOptimizations(): Boolean = app.scheduler.ignoringBatteryOptimizations()
}
