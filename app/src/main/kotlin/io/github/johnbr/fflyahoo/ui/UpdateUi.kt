package io.github.johnbr.fflyahoo.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.update.Release
import io.github.johnbr.fflyahoo.update.UpdateState

/**
 * Starts an update, first sending the user to "Install unknown apps" for this app if Android needs that
 * granted, and carrying on by itself when they come back with it allowed.
 */
@Composable
private fun rememberInstall(): (Release) -> Unit {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf(false) }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val release = (context.app.updater.state.value as? UpdateState.Available)?.release
        if (pending && release != null && context.packageManager.canRequestPackageInstalls()) context.app.updater.install(release)
        pending = false
    }
    return { release ->
        if (context.packageManager.canRequestPackageInstalls()) {
            context.app.updater.install(release)
        } else {
            pending = true
            grant.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        }
    }
}

private fun openPage(context: Context, release: Release) =
    context.startActivity(Intent(Intent.ACTION_VIEW, release.page.toUri()))

/** Above the tabs while an update is on offer or under way. "Later" hides it until the app is next opened. */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val state by context.app.updater.state.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val install = rememberInstall()
    val strip = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(start = 16.dp, end = 4.dp)
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    when (val s = state) {
        is UpdateState.Available -> if (dismissed != s.release.version) {
            Row(strip, verticalAlignment = Alignment.CenterVertically) {
                Text("Version ${s.release.version} is available.", Modifier.weight(1f), color = color, fontSize = 13.sp)
                TextButton(onClick = { dismissed = s.release.version }) { Text("Later") }
                TextButton(onClick = { install(s.release) }) { Text("Update") }
            }
        }
        is UpdateState.Downloading -> Column(strip.padding(vertical = 8.dp)) {
            Text("Downloading version ${s.release.version}…", color = color, fontSize = 13.sp)
            Progress(s.fraction)
        }
        is UpdateState.Failed -> if (s.release != null && dismissed != s.release.version) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                TextButton(onClick = { dismissed = s.release.version }) { Text("Later") }
                TextButton(onClick = { install(s.release) }) { Text("Retry") }
            }
        }
        else -> Unit
    }
}

/** Settings → About: this version, a manual check, and the update when there is one. */
@Composable
fun UpdateSection(version: String) {
    val context = LocalContext.current
    val updater = context.app.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val install = rememberInstall()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        val status = when (val s = state) {
            UpdateState.Idle -> null
            UpdateState.Checking -> "Checking for updates…"
            UpdateState.UpToDate -> "Up to date."
            is UpdateState.Available -> "Version ${s.release.version} is available."
            is UpdateState.Downloading -> "Downloading version ${s.release.version}…"
            is UpdateState.Installing -> "Installing version ${s.release.version}…"
            is UpdateState.Failed -> s.message
        }
        Text("Version $version" + (status?.let { " — $it" } ?: ""), fontSize = 13.sp, color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else muted)
        (state as? UpdateState.Downloading)?.let { Progress(it.fraction) }
        val release = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (release != null) {
                OutlinedButton(onClick = { install(release) }) { Text("Update to ${release.version}") }
                TextButton(onClick = { openPage(context, release) }) { Text("What's new") }
            } else {
                val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
                OutlinedButton(onClick = { updater.check() }, enabled = !busy) { Text("Check for updates") }
            }
        }
        Text(
            "Updates come from the app's GitHub releases page. Android asks you to confirm each install, and allowing it once under \"Install unknown apps\".",
            fontSize = 11.sp,
            color = muted,
        )
    }
}

@Composable
private fun Progress(fraction: Float?) {
    val modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    if (fraction == null) LinearProgressIndicator(modifier) else LinearProgressIndicator(progress = { fraction }, modifier = modifier)
}
