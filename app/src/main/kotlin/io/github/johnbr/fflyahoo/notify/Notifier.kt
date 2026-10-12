package io.github.johnbr.fflyahoo.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.johnbr.fflyahoo.R
import io.github.johnbr.fflyahoo.core.engine.EngineEvent
import io.github.johnbr.fflyahoo.core.engine.LeagueEngine
import io.github.johnbr.fflyahoo.core.present.Present
import io.github.johnbr.fflyahoo.data.EngineHub
import io.github.johnbr.fflyahoo.data.SettingsStore
import io.github.johnbr.fflyahoo.live.LiveScheduler
import io.github.johnbr.fflyahoo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Scoring alerts — what the integration's automation hooks were for: "my player scored", "opponent
 * scored", "lead change". Only while live mode is running; corrections and bench players never alert.
 * Each play keeps one notification: a later piece of it, or its play text landing, updates it in place.
 */
class Notifier(
    private val context: Context,
    private val hub: EngineHub,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val scheduler: () -> LiveScheduler,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val watchers = HashMap<String, Job>()
    private val posted = HashSet<String>()

    fun start() {
        createChannels()
        scope.launch {
            hub.engines.collect { engines ->
                for ((id, job) in watchers.entries.toList()) if (engines[id] == null) job.cancel().also { watchers.remove(id) }
                for ((id, engine) in engines) if (watchers[id] == null) watchers[id] = scope.launch { watch(engine) }
            }
        }
    }

    private suspend fun watch(engine: LeagueEngine) = kotlinx.coroutines.coroutineScope {
        launch { engine.events.collect { onEvent(engine, it) } }
        launch {
            // Lead changes: the leader of my matchup moving from one team to the other.
            var lastLeader: String? = null
            var lastWeek: Int? = null
            engine.state.map { ui ->
                val mine = ui.myTeam ?: return@map null
                val row = ui.scoreboard.matchups.firstOrNull { it.matchupId == mine.matchupId } ?: return@map null
                Triple(ui.scoreboard.week, row.leader, ui)
            }.distinctUntilChanged { a, b -> a?.first == b?.first && a?.second == b?.second }.collect { seen ->
                val (week, leader, ui) = seen ?: return@collect
                if (week != lastWeek) {
                    lastWeek = week
                    lastLeader = leader
                    return@collect
                }
                if (leader == null) return@collect // a tie is not a lead
                val previous = lastLeader
                lastLeader = leader
                if (previous == null || previous == leader) return@collect
                val s = settings.current()
                if (!s.notifyLeadChange || !scheduler().running.value) return@collect
                val mine = ui.myTeam ?: return@collect
                val title = if (leader == mine.me.teamId) "You took the lead" else "${mine.opponent.name} took the lead"
                post(
                    "lead:${engine.leagueId}".hashCode(),
                    build(CHANNEL_LEAD, title, "${mine.me.name} ${Present.points(mine.me.points)} – ${Present.points(mine.opponent.points)} ${mine.opponent.name}"),
                )
            }
        }
    }

    private suspend fun onEvent(engine: LeagueEngine, e: EngineEvent) {
        val event = e.event
        if (event.correction || !event.starter) return
        val ui = engine.state.value
        val mine = ui.myTeam ?: return
        val isMine = event.teamKey.endsWith(".t.${mine.me.teamId}")
        val isOpponent = event.teamKey.endsWith(".t.${mine.opponent.teamId}")
        if (!isMine && !isOpponent) return
        val s = settings.current()
        if (!scheduler().running.value) return
        if (isMine && !s.notifyMine || isOpponent && !s.notifyOpponent) return
        val key = "${engine.leagueId}:${event.eventId}"
        // A revision only rewrites a notification that is already up; it never raises a new one.
        if (e.revised && key !in posted) return
        posted += key
        val title = "${e.row.shortWho} ${Present.delta(e.row.delta)}" + if (isOpponent) " · ${mine.opponent.name}" else ""
        post(key.hashCode(), build(if (isMine) CHANNEL_MINE else CHANNEL_OPPONENT, title, e.row.text, silent = e.revised))
    }

    private fun build(channel: String, title: String, text: String, silent: Boolean = false): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setGroup(channel)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
    }

    fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(id, notification)
    }

    private fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LIVE, "Live scores", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The ongoing notification while live mode is running"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_MINE, "My player scored", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_OPPONENT, "Opponent scored", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_LEAD, "Lead changed", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    companion object {
        const val CHANNEL_LIVE = "live"
        const val CHANNEL_MINE = "mine"
        const val CHANNEL_OPPONENT = "opponent"
        const val CHANNEL_LEAD = "lead"
        const val LIVE_ID = 1
    }
}
