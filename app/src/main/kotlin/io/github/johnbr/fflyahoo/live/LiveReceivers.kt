package io.github.johnbr.fflyahoo.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.johnbr.fflyahoo.app
import kotlinx.coroutines.launch

/** The exact alarm before kickoff: start the service (an exact alarm may start one from the background). */
class LiveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.app.scheduler.startNow()
    }
}

/** After a reboot or an app update, alarms are gone: re-arm them. */
class LiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        context.app.scope.launch {
            try {
                refreshSlate(context)
                context.app.scheduler.reschedule(canStartNow = false)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Every 30 minutes while live mode is on: refresh the slate and re-arm the alarm. */
class LiveCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        refreshSlate(applicationContext)
        applicationContext.app.scheduler.reschedule(canStartNow = false)
        return Result.success()
    }
}

/** One refresh of the first league, so the schedule is computed from a current slate. */
internal suspend fun refreshSlate(context: Context) {
    val hub = context.app.hub
    val settings = context.app.settings.current()
    val league = settings.selectedLeague ?: return
    if (hub.engines.value[league.leagueId]?.state?.value?.updatedAt == null || !hub.active) hub.refreshOnce(league.leagueId)
}
