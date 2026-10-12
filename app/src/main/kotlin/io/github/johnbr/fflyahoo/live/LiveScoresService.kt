package io.github.johnbr.fflyahoo.live

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.johnbr.fflyahoo.R
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.core.engine.LeagueUi
import io.github.johnbr.fflyahoo.core.present.Present
import io.github.johnbr.fflyahoo.notify.Notifier
import io.github.johnbr.fflyahoo.ui.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Live mode: keeps the engines polling with the app closed, through a game window.
 *
 * A `specialUse` foreground service ("live sports score tracking"): `dataSync` would be capped at six
 * hours a day from Android 15, and a full Sunday slate runs longer. Its ongoing notification carries
 * the user's matchup. It stops itself once nothing is live and the next kickoff is a while off, and
 * the scheduler's alarm brings it back before that kickoff.
 */
class LiveScoresService : LifecycleService() {
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            Notifier.LIVE_ID,
            ongoing(null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!started) {
            started = true
            app.scheduler.setRunning(true)
            app.hub.acquire(OWNER)
            watch()
        }
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watch() {
        val hub = app.hub
        // The ongoing notification follows the selected league's matchup.
        lifecycleScope.launch {
            combine(app.settings.settings, hub.engines) { s, engines -> s.selectedLeague?.let { engines[it.leagueId] } }
                .flatMapLatest { it?.state ?: flowOf(null) }
                .collect { ui -> app.notifier.post(Notifier.LIVE_ID, ongoing(ui)) }
        }
        // Stop once nothing is live and the next kickoff is more than the idle gap away.
        lifecycleScope.launch {
            delay(90_000) // give the first polls time to land
            while (true) {
                val games = app.scheduler.slate()
                val now = System.currentTimeMillis() / 1000
                val live = games.any { it.state == "in" || it.state == "delayed" }
                val next = LiveScheduler.nextKickoff(games, now)
                if (games.isNotEmpty() && !live && (next == null || next - now > LiveScheduler.IDLE_GAP_SECONDS)) {
                    stop()
                    return@launch
                }
                delay(60_000)
            }
        }
    }

    private fun stop() {
        if (started) {
            app.hub.release(OWNER)
            started = false
        }
        app.scheduler.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        app.scope.launch { app.scheduler.reschedule(canStartNow = false) }
    }

    override fun onDestroy() {
        if (started) app.hub.release(OWNER)
        app.scheduler.setRunning(false)
        super.onDestroy()
    }

    private fun ongoing(ui: LeagueUi?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveScoresService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val mine = ui?.myTeam
        val (title, text) = when {
            mine != null -> "${mine.me.name} ${Present.points(mine.me.points)} – ${Present.points(mine.opponent.points)} ${mine.opponent.name}" to
                "proj ${Present.points(mine.me.liveProjected)} – ${Present.points(mine.opponent.liveProjected)}" +
                (ui.scoreboard.activeGames.takeIf { it > 0 }?.let { " · $it live" } ?: "")
            ui != null -> (ui.scoreboard.leagueName.ifEmpty { "Live scores" }) to
                (ui.scoreboard.activeGames.takeIf { it > 0 }?.let { "$it games live" } ?: "Waiting for kickoff")
            else -> "Live scores" to "Starting…"
        }
        return NotificationCompat.Builder(this, Notifier.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.johnbr.fflyahoo.STOP_LIVE"
        private const val OWNER = "service"
    }
}
