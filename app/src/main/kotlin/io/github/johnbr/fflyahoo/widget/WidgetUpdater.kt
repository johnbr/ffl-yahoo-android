package io.github.johnbr.fflyahoo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.core.engine.LeagueUi
import io.github.johnbr.fflyahoo.core.present.Present
import io.github.johnbr.fflyahoo.data.EngineHub
import io.github.johnbr.fflyahoo.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

private fun nowSeconds() = System.currentTimeMillis() / 1000.0

/** Reads a snapshot saved by an older version, which carried fields this one has dropped. */
private val snapshotJson = Json { ignoreUnknownKeys = true }

/** What the widget draws — written whenever the selected league's matchup changes. */
@Serializable
data class WidgetSnapshot(
    val myName: String,
    val myPoints: String,
    val myProjected: String,
    val opponentName: String,
    val opponentPoints: String,
    val opponentProjected: String,
    /** The matchup's last play, or `""` once its [Present.PLAY_LINE_SECONDS] are up. */
    val lastPlay: String,
    /** `me` / `opponent` — who is ahead; empty on a tie or before a score. */
    val leader: String = "",
    /** Each live projection against the morning's: `UP` / `DOWN` / `FLAT`, empty when there is none. */
    val myTrend: String = "",
    val opponentTrend: String = "",
    /** No starter on either side has a game left: the leader has won. */
    val final: Boolean = false,
    /** Starters with a game still to finish, in progress or yet to kick off; null when the tier can't count. */
    val myRemaining: Int? = null,
    val opponentRemaining: Int? = null,
    /** When [lastPlay] was scored, epoch seconds; 0 when unknown. */
    val lastPlayAt: Double = 0.0,
) {
    /** This snapshot, without its play line if that has had its [Present.PLAY_LINE_SECONDS] by [now]. */
    fun expired(now: Double): WidgetSnapshot =
        if (lastPlay.isNotEmpty() && Present.playLineLeft(lastPlayAt, now) == 0.0) copy(lastPlay = "") else this

    companion object {
        private fun file(context: Context) = File(context.filesDir, "widget.json")

        fun read(context: Context): WidgetSnapshot? =
            runCatching { snapshotJson.decodeFromString(serializer(), file(context).readText()) }.getOrNull()

        fun write(context: Context, snapshot: WidgetSnapshot?) {
            val f = file(context)
            if (snapshot == null) f.delete() else f.writeText(Json.encodeToString(serializer(), snapshot))
        }

        fun of(ui: LeagueUi, now: Double = nowSeconds()): WidgetSnapshot? {
            val mine = ui.myTeam ?: return null
            val row = ui.scoreboard.matchups.firstOrNull { it.matchupId == mine.matchupId }
            val play = row?.lastPlay
            return WidgetSnapshot(
                myName = mine.me.name,
                myPoints = Present.points(mine.me.points),
                myProjected = Present.points(mine.me.liveProjected),
                opponentName = mine.opponent.name,
                opponentPoints = Present.points(mine.opponent.points),
                opponentProjected = Present.points(mine.opponent.liveProjected),
                lastPlay = play?.takeIf { Present.playLineLeft(it.timestamp, now) > 0.0 }?.shortText ?: "",
                myTrend = Present.trend(mine.me.liveProjected, mine.me.projected)?.name ?: "",
                opponentTrend = Present.trend(mine.opponent.liveProjected, mine.opponent.projected)?.name ?: "",
                final = row?.final ?: false,
                leader = when (row?.leader) {
                    null -> ""
                    mine.me.teamId -> "me"
                    else -> "opponent"
                },
                myRemaining = mine.me.remaining,
                opponentRemaining = mine.opponent.remaining,
                lastPlayAt = play?.timestamp ?: 0.0,
            )
        }
    }
}

/** Pushes the selected league's matchup to the widget, and keeps a slow refresh going for it. */
class WidgetUpdater(
    private val context: Context,
    private val hub: EngineHub,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    /**
     * What the widget shows, starting from the saved file. A widget session reads the data once, when it
     * starts, and a running session is only recomposed by an update — so it collects this, and every
     * change redraws it.
     */
    val snapshot: MutableStateFlow<WidgetSnapshot?> by lazy { MutableStateFlow(WidgetSnapshot.read(context)) }

    /** Saves [value] for a widget drawn later, and hands it to any widget showing now. */
    suspend fun publish(value: WidgetSnapshot?) = withContext(Dispatchers.IO) {
        WidgetSnapshot.write(context, value)
        snapshot.value = value
        scheduleExpiry(value)
    }

    /**
     * Takes the play line down when its time is up. A widget draws only when told to, and the score may
     * not move again for an hour, so the expiry gets its own one-off job — work rather than a coroutine,
     * so it still runs when the process has gone. Each publish replaces the last one's job.
     */
    private fun scheduleExpiry(value: WidgetSnapshot?) {
        val left = value?.takeIf { it.lastPlay.isNotEmpty() }?.let { Present.playLineLeft(it.lastPlayAt, nowSeconds()) } ?: return
        if (!left.isFinite()) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget-play-expiry",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PlayExpiryWorker>()
                .setInitialDelay((left * 1000).toLong() + 500, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            combine(settings.settings, hub.engines) { s, engines -> s.selectedLeague to engines }
                .flatMapLatest { (league, engines) ->
                    if (league == null) {
                        flowOf(null)
                    } else {
                        // An engine not built yet, or not polled yet (both happen at every process start),
                        // knows nothing: keep the saved snapshot rather than blank the widget until then.
                        engines[league.leagueId]?.state?.filter { it.updatedAt != null }?.map(WidgetSnapshot::of) ?: emptyFlow()
                    }
                }
                .distinctUntilChanged()
                .collect { snapshot ->
                    // Written whether or not a widget exists yet: one added later reads this file on
                    // its first draw, and nothing else would tell it the score until the next change.
                    publish(snapshot)
                    if (hasWidgets()) MatchupWidget().updateAll(context)
                }
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "widget-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    suspend fun hasWidgets(): Boolean =
        GlanceAppWidgetManager(context).getGlanceIds(MatchupWidget::class.java).isNotEmpty()
}

/** When the widget's play line has had its [Present.PLAY_LINE_SECONDS]: redraw it without the play. */
class PlayExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val widgets = applicationContext.app.widgets
        val current = widgets.snapshot.value ?: return Result.success()
        val expired = current.expired(nowSeconds())
        if (expired != current) {
            widgets.publish(expired)
            if (widgets.hasWidgets()) MatchupWidget().updateAll(applicationContext)
        }
        return Result.success()
    }
}

/** Every 30 minutes: one refresh of the selected league, when nothing else is polling it. */
class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.widgets.hasWidgets() || app.hub.active) return Result.success()
        val league = app.settings.current().selectedLeague ?: return Result.success()
        app.hub.refreshOnce(league.leagueId)
        return Result.success()
    }
}
