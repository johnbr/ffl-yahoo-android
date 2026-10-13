package io.github.johnbr.fflyahoo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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

/** What the widget draws — written whenever the selected league's matchup changes. */
@Serializable
data class WidgetSnapshot(
    val leagueName: String,
    val myName: String,
    val myPoints: String,
    val myProjected: String,
    val opponentName: String,
    val opponentPoints: String,
    val opponentProjected: String,
    val lastPlay: String,
    val activeGames: Int,
    /** `me` / `opponent` — who is ahead; empty on a tie or before a score. */
    val leader: String = "",
    /** Each live projection against the morning's: `UP` / `DOWN` / `FLAT`, empty when there is none. */
    val myTrend: String = "",
    val opponentTrend: String = "",
    /** No starter on either side has a game left: the leader has won. */
    val final: Boolean = false,
) {
    companion object {
        private fun file(context: Context) = File(context.filesDir, "widget.json")

        fun read(context: Context): WidgetSnapshot? =
            runCatching { Json.decodeFromString(serializer(), file(context).readText()) }.getOrNull()

        fun write(context: Context, snapshot: WidgetSnapshot?) {
            val f = file(context)
            if (snapshot == null) f.delete() else f.writeText(Json.encodeToString(serializer(), snapshot))
        }

        fun of(ui: LeagueUi): WidgetSnapshot? {
            val mine = ui.myTeam ?: return null
            val row = ui.scoreboard.matchups.firstOrNull { it.matchupId == mine.matchupId }
            return WidgetSnapshot(
                leagueName = ui.scoreboard.leagueName,
                myName = mine.me.name,
                myPoints = Present.points(mine.me.points),
                myProjected = Present.points(mine.me.liveProjected),
                opponentName = mine.opponent.name,
                opponentPoints = Present.points(mine.opponent.points),
                opponentProjected = Present.points(mine.opponent.liveProjected),
                lastPlay = row?.lastPlay?.shortText ?: "",
                activeGames = ui.scoreboard.activeGames,
                myTrend = Present.trend(mine.me.liveProjected, mine.me.projected)?.name ?: "",
                opponentTrend = Present.trend(mine.opponent.liveProjected, mine.opponent.projected)?.name ?: "",
                final = row?.final ?: false,
                leader = when (row?.leader) {
                    null -> ""
                    mine.me.teamId -> "me"
                    else -> "opponent"
                },
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
