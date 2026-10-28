package io.github.johnbr.fflyahoo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.johnbr.fflyahoo.BuildConfig
import io.github.johnbr.fflyahoo.data.AppSettings
import io.github.johnbr.fflyahoo.data.LeagueConfig
import kotlinx.coroutines.launch

/**
 * Add a league: the id (or the league's URL, pasted from the address bar), then which team is yours.
 * No account, no sign-in — this works for private leagues too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLeagueScreen(vm: MainViewModel, editing: LeagueConfig? = null, onDone: () -> Unit, onBack: (() -> Unit)?) {
    var input by remember { mutableStateOf(editing?.leagueId ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<FoundLeague?>(null) }
    var team by remember { mutableStateOf(editing?.teamId) }
    val scope = rememberCoroutineScope()

    fun lookUp() {
        busy = true
        error = null
        scope.launch {
            try {
                found = vm.find(input)
            } catch (e: IllegalArgumentException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    if (editing != null && found == null && !busy && error == null) lookUp()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing != null) "Choose your team" else "Add a league") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            val league = found
            if (league == null) {
                Text("Your Yahoo league id is the number in your league's address:", style = MaterialTheme.typography.bodyMedium)
                Text("football.fantasysports.yahoo.com/f1/802904", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("League id or URL") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (input.isNotBlank()) lookUp() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::lookUp, enabled = input.isNotBlank() && !busy) { Text("Find league") }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 16.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Works for private leagues too — no Yahoo account or sign-in. Scores come from the same public feeds Yahoo's GameChannel page uses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(league.name, style = MaterialTheme.typography.titleLarge)
                Text("Which team is yours? Your matchup gets its own tab, the widget, and scoring alerts.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                (listOf<Pair<String?, String>>(null to "None — just the league scoreboard") + league.teams.map { it.key to it.value }).forEach { (id, name) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = team == id, onClick = { team = id }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = team == id, onClick = { team = id })
                        Text(name)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    vm.save(league, team)
                    onDone()
                }) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, settings: AppSettings, onBack: () -> Unit, onAddLeague: () -> Unit, onEditLeague: (LeagueConfig) -> Unit) {
    val context = LocalContext.current
    val running by vm.liveServiceRunning.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Leagues")
            settings.leagues.forEach { league ->
                Row(Modifier.fillMaxWidth().clickable { onEditLeague(league) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(league.name, fontWeight = FontWeight.SemiBold)
                        Text(league.teamName?.let { "My team: $it" } ?: "No team chosen", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.remove(league.leagueId) }) { Text("Remove") }
                }
            }
            TextButton(onClick = onAddLeague, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add a league") }
            HorizontalDivider()

            Section("Live mode")
            Text(
                "Keeps scores updating during games with the app closed, and sends scoring alerts for your matchup. It starts itself shortly before kickoff and stops when your games are over, with an ongoing notification while it runs.",
                Modifier.padding(horizontal = 16.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Live mode", settings.liveMode) { on ->
                if (on && !notificationsAllowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.setLiveMode(on)
            }
            if (settings.liveMode) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) OutlinedButton(onClick = vm::stopLiveNow) { Text("Stop for now") } else OutlinedButton(onClick = vm::startLiveNow) { Text("Start now") }
                }
                if (!notificationsAllowed) {
                    Warning("Notifications are off for this app, so there will be no alerts.") {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (!vm.canScheduleExactAlarms()) {
                    Warning("Allow alarms & reminders so live mode can start itself at kickoff.") {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                    }
                }
                if (!vm.ignoringBatteryOptimizations()) {
                    Warning("Optional: exempt the app from battery optimisation for steadier updates during long game days.") {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                SwitchRow("My player scored", settings.notifyMine) { vm.setNotify(mine = it) }
                SwitchRow("Opponent scored", settings.notifyOpponent) { vm.setNotify(opponent = it) }
                SwitchRow("Lead changed", settings.notifyLeadChange) { vm.setNotify(leadChange = it) }
            }
            HorizontalDivider()

            Section("About")
            Text(
                "Fantasy data provided by Yahoo Fantasy. Past weeks' player stat lines and the NFL schedule come from ESPN. " +
                    "This app is not affiliated with, endorsed by, or sponsored by Yahoo, ESPN or the NFL. " +
                    "Points are computed from Yahoo's live stat feed with your league's own scoring settings; Yahoo's own live scoring trails the play by 30–60 seconds.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Warning(text: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onFix).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        Text("Fix", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}
