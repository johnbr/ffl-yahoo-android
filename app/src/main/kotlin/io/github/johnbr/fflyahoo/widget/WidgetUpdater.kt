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
import io.github.johnbr.fflyahoo.core.state.MatchupRow
import io.github.johnbr.fflyahoo.core.state.TeamRow
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

/**
 * One matchup as a widget draws it — written whenever the selected league's matchup changes. The league
 * widget stacks one per matchup (see [LeagueSnapshot]), with "me" as home and "opponent" as away.
 */
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
    /** `w7.m3`: which matchup a tap on it opens in the app. */
    val matchupId: String = "",
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
            return of(mine.me, mine.opponent, ui.scoreboard.matchups.firstOrNull { it.matchupId == mine.matchupId }, now)
        }

        /** [first] on top as "me", [second] under it as "opponent". */
        fun of(first: TeamRow, second: TeamRow, row: MatchupRow?, now: Double): WidgetSnapshot {
            val play = row?.lastPlay
            return WidgetSnapshot(
                myName = first.name,
                myPoints = Present.points(first.points),
                myProjected = Present.points(first.liveProjected),
                opponentName = second.name,
                opponentPoints = Present.points(second.points),
                opponentProjected = Present.points(second.liveProjected),
                lastPlay = play?.takeIf { Present.playLineLeft(it.timestamp, now) > 0.0 }?.shortText ?: "",
                myTrend = Present.trend(first.liveProjected, first.projected)?.name ?: "",
                opponentTrend = Present.trend(second.liveProjected, second.projected)?.name ?: "",
                final = row?.final ?: false,
                leader = when (row?.leader) {
                    null -> ""
                    first.teamId -> "me"
                    else -> "opponent"
                },
                myRemaining = first.remaining,
                opponentRemaining = second.remaining,
                lastPlayAt = play?.timestamp ?: 0.0,
                matchupId = row?.matchupId ?: "",
            )
        }
    }
}

/** What the league widget draws: every matchup, in the league's order, home team on top. */
@Serializable
data class LeagueSnapshot(val matchups: List<WidgetSnapshot>) {
    /** This snapshot, without each play line that has had its [Present.PLAY_LINE_SECONDS] by [now]. */
    fun expired(now: Double): LeagueSnapshot = copy(matchups = matchups.map { it.expired(now) })

    companion object {
        private fun file(context: Context) = File(context.filesDir, "widget-league.json")

        fun read(context: Context): LeagueSnapshot? =
            runCatching { snapshotJson.decodeFromString(serializer(), file(context).readText()) }.getOrNull()

        fun write(context: Context, snapshot: LeagueSnapshot?) {
            val f = file(context)
            if (snapshot == null) f.delete() else f.writeText(Json.encodeToString(serializer(), snapshot))
        }

        fun of(ui: LeagueUi, now: Double = nowSeconds()): LeagueSnapshot? =
            ui.scoreboard.matchups.takeIf { it.isNotEmpty() }
                ?.let { rows -> LeagueSnapshot(rows.map { WidgetSnapshot.of(it.home, it.away, it, now) }) }
    }
}

/** Pushes the selected league to the widgets, and keeps a slow refresh going for it. */
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

    /** What the league widget shows, the same way. */
    val league: MutableStateFlow<LeagueSnapshot?> by lazy { MutableStateFlow(LeagueSnapshot.read(context)) }

    /** Saves [value] for a widget drawn later, and hands it to any widget showing now. */
    suspend fun publish(value: WidgetSnapshot?) = withContext(Dispatchers.IO) {
        WidgetSnapshot.write(context, value)
        snapshot.value = value
        scheduleExpiry()
    }

    /** As [publish], for the league widget. */
    suspend fun publish(value: LeagueSnapshot?) = withContext(Dispatchers.IO) {
        LeagueSnapshot.write(context, value)
        league.value = value
        scheduleExpiry()
    }

    /** Redraws whichever widgets are on the home screen. */
    suspend fun redraw() {
        val manager = GlanceAppWidgetManager(context)
        if (manager.getGlanceIds(MatchupWidget::class.java).isNotEmpty()) MatchupWidget().updateAll(context)
        if (manager.getGlanceIds(LeagueWidget::class.java).isNotEmpty()) LeagueWidget().updateAll(context)
    }

    /**
     * Takes the play line down when its time is up. A widget draws only when told to, and the score may
     * not move again for an hour, so the expiry gets its own one-off job — work rather than a coroutine,
     * so it still runs when the process has gone. Each publish replaces the last one's job with one for
     * the first play line, on either widget, still to go.
     */
    private fun scheduleExpiry() {
        val now = nowSeconds()
        val left = (listOfNotNull(snapshot.value) + league.value?.matchups.orEmpty())
            .filter { it.lastPlay.isNotEmpty() }
            .map { Present.playLineLeft(it.lastPlayAt, now) }
            .filter { it.isFinite() }
            .minOrNull() ?: return
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
                        engines[league.leagueId]?.state?.filter { it.updatedAt != null }
                            ?.map { WidgetSnapshot.of(it) to LeagueSnapshot.of(it) } ?: emptyFlow()
                    }
                }
                .distinctUntilChanged()
                .collect { pair ->
                    // Written whether or not a widget exists yet: one added later reads this file on
                    // its first draw, and nothing else would tell it the score until the next change.
                    publish(pair?.first)
                    publish(pair?.second)
                    redraw()
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

    suspend fun hasWidgets(): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(MatchupWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(LeagueWidget::class.java).isNotEmpty()
    }
}

/** When a widget's play line has had its [Present.PLAY_LINE_SECONDS]: redraw it without the play. */
class PlayExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val widgets = applicationContext.app.widgets
        val now = nowSeconds()
        var changed = false
        widgets.snapshot.value?.let { current ->
            val expired = current.expired(now)
            if (expired != current) widgets.publish(expired).also { changed = true }
        }
        widgets.league.value?.let { current ->
            val expired = current.expired(now)
            if (expired != current) widgets.publish(expired).also { changed = true }
        }
        if (changed) widgets.redraw()
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
