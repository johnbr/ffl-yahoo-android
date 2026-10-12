package io.github.johnbr.fflyahoo.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.core.state.NflGameRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * When live mode runs. An exact alarm shortly before the next kickoff starts the service (an exact
 * alarm is one of the few things allowed to start a foreground service from the background); a
 * periodic check every 30 minutes re-arms it in case the slate moved or the alarm was lost.
 */
class LiveScheduler(private val context: Context) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun setRunning(on: Boolean) {
        _running.value = on
    }

    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun ignoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Start the service now — from the app, which is in the foreground. */
    fun startNow() {
        ContextCompat.startForegroundService(context, Intent(context, LiveScoresService::class.java))
    }

    fun stopNow() {
        context.startService(Intent(context, LiveScoresService::class.java).setAction(LiveScoresService.ACTION_STOP))
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, LiveAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Re-arm everything from the current settings and slate. Starts the service directly when a game is
     * already on and the caller is allowed to (the app, or the alarm).
     */
    suspend fun reschedule(canStartNow: Boolean = true) {
        val settings = context.app.settings.current()
        val work = WorkManager.getInstance(context)
        if (!settings.liveMode) {
            alarms.cancel(alarmIntent())
            work.cancelUniqueWork(WORK)
            if (_running.value) stopNow()
            return
        }
        work.enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LiveCheckWorker>(30, TimeUnit.MINUTES).build(),
        )
        val games = slate()
        val now = System.currentTimeMillis() / 1000
        if (games.any { it.state == "in" || it.state == "delayed" } || nextKickoff(games, now)?.let { it - now <= LEAD_SECONDS } == true) {
            if (canStartNow && !_running.value) startNow()
            return
        }
        val next = nextKickoff(games, now) ?: return
        val at = (next - LEAD_SECONDS) * 1000
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        }
    }

    /** The NFL slate as the engines last saw it — the same for every league. */
    fun slate(): List<NflGameRow> = context.app.hub.engines.value.values.firstNotNullOfOrNull { e -> e.state.value.nfl.games.ifEmpty { null } } ?: emptyList()

    companion object {
        private const val WORK = "live-check"

        /** Start this long before kickoff, and stay up across gaps shorter than [IDLE_GAP_SECONDS]. */
        const val LEAD_SECONDS = 10 * 60L
        const val IDLE_GAP_SECONDS = 30 * 60L

        fun nextKickoff(games: List<NflGameRow>, now: Long): Long? =
            games.filter { it.state == "pre" }.mapNotNull { it.startTime }.filter { it > now - 3600 }.minOrNull()
    }
}
