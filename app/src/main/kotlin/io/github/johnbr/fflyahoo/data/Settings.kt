package io.github.johnbr.fflyahoo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One followed league — what the integration's config entry held. */
@Serializable
data class LeagueConfig(
    val leagueId: String,
    /** The league's display name, from the seed when it was added. */
    val name: String,
    /** Which team is "mine". Optional: without it there is no My Matchup and no scoring alerts. */
    val teamId: String? = null,
    val teamName: String? = null,
)

data class AppSettings(
    val leagues: List<LeagueConfig> = emptyList(),
    val selected: String? = null,
    /** Run the live service through game windows. Off until the user turns it on. */
    val liveMode: Boolean = false,
    val notifyMine: Boolean = true,
    val notifyOpponent: Boolean = true,
    val notifyLeadChange: Boolean = true,
) {
    val selectedLeague: LeagueConfig? get() = leagues.firstOrNull { it.leagueId == selected } ?: leagues.firstOrNull()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** Settings and followed leagues, in DataStore. */
class SettingsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val leaguesSerializer = ListSerializer(LeagueConfig.serializer())

    private object Keys {
        val LEAGUES = stringPreferencesKey("leagues")
        val SELECTED = stringPreferencesKey("selected")
        val LIVE_MODE = booleanPreferencesKey("live_mode")
        val NOTIFY_MINE = booleanPreferencesKey("notify_mine")
        val NOTIFY_OPPONENT = booleanPreferencesKey("notify_opponent")
        val NOTIFY_LEAD = booleanPreferencesKey("notify_lead")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            leagues = prefs[Keys.LEAGUES]?.let { runCatching { json.decodeFromString(leaguesSerializer, it) }.getOrNull() } ?: emptyList(),
            selected = prefs[Keys.SELECTED],
            liveMode = prefs[Keys.LIVE_MODE] ?: false,
            notifyMine = prefs[Keys.NOTIFY_MINE] ?: true,
            notifyOpponent = prefs[Keys.NOTIFY_OPPONENT] ?: true,
            notifyLeadChange = prefs[Keys.NOTIFY_LEAD] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** Add or replace a league (matched by id), and select it. */
    suspend fun putLeague(league: LeagueConfig) = context.dataStore.edit { prefs ->
        val leagues = decode(prefs).filterNot { it.leagueId == league.leagueId } + league
        prefs[Keys.LEAGUES] = json.encodeToString(leaguesSerializer, leagues)
        prefs[Keys.SELECTED] = league.leagueId
    }

    suspend fun removeLeague(leagueId: String) = context.dataStore.edit { prefs ->
        val leagues = decode(prefs).filterNot { it.leagueId == leagueId }
        prefs[Keys.LEAGUES] = json.encodeToString(leaguesSerializer, leagues)
        if (prefs[Keys.SELECTED] == leagueId) {
            leagues.firstOrNull()?.let { prefs[Keys.SELECTED] = it.leagueId } ?: prefs.remove(Keys.SELECTED)
        }
    }

    suspend fun select(leagueId: String) = context.dataStore.edit { it[Keys.SELECTED] = leagueId }

    suspend fun setLiveMode(on: Boolean) = context.dataStore.edit { it[Keys.LIVE_MODE] = on }

    suspend fun setNotify(mine: Boolean? = null, opponent: Boolean? = null, leadChange: Boolean? = null) = context.dataStore.edit { prefs ->
        mine?.let { prefs[Keys.NOTIFY_MINE] = it }
        opponent?.let { prefs[Keys.NOTIFY_OPPONENT] = it }
        leadChange?.let { prefs[Keys.NOTIFY_LEAD] = it }
    }

    private fun decode(prefs: Preferences): List<LeagueConfig> =
        prefs[Keys.LEAGUES]?.let { runCatching { json.decodeFromString(leaguesSerializer, it) }.getOrNull() } ?: emptyList()
}
