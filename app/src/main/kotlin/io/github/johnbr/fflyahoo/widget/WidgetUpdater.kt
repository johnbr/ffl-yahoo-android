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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            combine(settings.settings, hub.engines) { s, engines -> s.selectedLeague?.let { engines[it.leagueId] } }
                .flatMapLatest { it?.state ?: flowOf(null) }
                .map { ui -> ui?.let(WidgetSnapshot::of) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (!hasWidgets()) return@collect
                    withContext(Dispatchers.IO) { WidgetSnapshot.write(context, snapshot) }
                    MatchupWidget().updateAll(context)
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
