package io.github.johnbr.fflyahoo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.johnbr.fflyahoo.app

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not again on a rotation: the request was already handled when the activity first started.
        if (savedInstanceState == null) takeMatchup(intent)
        enableEdgeToEdge()
        setContent {
            FflTheme {
                Surface { AppRoot(vm) }
            }
        }
    }

    // singleTop: a widget tap while the app is already open arrives here rather than in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeMatchup(intent)
    }

    private fun takeMatchup(intent: Intent?) {
        intent?.getStringExtra(EXTRA_MATCHUP)?.takeIf { it.isNotEmpty() }?.let { vm.openMatchup.value = it }
    }

    // The app polls while it is on screen; the live service keeps it polling when it is not.
    override fun onStart() {
        super.onStart()
        app.hub.acquire(OWNER)
        app.hub.refreshNow()
    }

    override fun onStop() {
        app.hub.release(OWNER)
        super.onStop()
    }

    companion object {
        private const val OWNER = "ui"

        /** The matchup a league-widget tap asks for, `w7.m3`. */
        const val EXTRA_MATCHUP = "matchup"
    }
}

private sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data object AddLeague : Screen
    data class EditLeague(val leagueId: String) : Screen
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.league.collectAsStateWithLifecycle()
    val s = settings ?: return // DataStore has not answered yet
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Main) }
    // A widget tap shows its matchup whatever screen the app was left on.
    val asked by vm.openMatchup.collectAsStateWithLifecycle()
    if (asked != null && screen != Screen.Main) screen = Screen.Main

    if (s.leagues.isEmpty()) {
        AddLeagueScreen(vm, onDone = { screen = Screen.Main }, onBack = null)
        return
    }
    BackHandler(enabled = screen != Screen.Main) {
        screen = if (screen is Screen.EditLeague || screen == Screen.AddLeague) Screen.Settings else Screen.Main
    }
    when (val current = screen) {
        Screen.Main -> MainScreen(s, ui, vm.selectedEngine, vm, onSettings = { screen = Screen.Settings }, onAddLeague = { screen = Screen.AddLeague })
        Screen.Settings -> SettingsScreen(
            vm, s,
            onBack = { screen = Screen.Main },
            onAddLeague = { screen = Screen.AddLeague },
            onEditLeague = { screen = Screen.EditLeague(it.leagueId) },
        )
        Screen.AddLeague -> AddLeagueScreen(vm, onDone = { screen = Screen.Main }, onBack = { screen = Screen.Main })
        is Screen.EditLeague -> AddLeagueScreen(
            vm,
            editing = s.leagues.firstOrNull { it.leagueId == current.leagueId },
            onDone = { screen = Screen.Settings },
            onBack = { screen = Screen.Settings },
        )
    }
}

private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Main -> "main"
            Screen.Settings -> "settings"
            Screen.AddLeague -> "add"
            is Screen.EditLeague -> "edit:${it.leagueId}"
        }
    },
    restore = {
        when {
            it == "settings" -> Screen.Settings
            it == "add" -> Screen.AddLeague
            it.startsWith("edit:") -> Screen.EditLeague(it.removePrefix("edit:"))
            else -> Screen.Main
        }
    },
)
