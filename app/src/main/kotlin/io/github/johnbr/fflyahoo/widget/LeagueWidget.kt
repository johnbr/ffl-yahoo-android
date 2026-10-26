package io.github.johnbr.fflyahoo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.ui.MainActivity

/**
 * Every matchup in the league on the home screen, stacked, each drawn as the single-matchup widget draws
 * one: home team over away, the live projections and starters left once it is wide enough, and the
 * last play for three minutes after it scores. The figures line up down the whole stack, and a stack
 * taller than the widget scrolls.
 */
class LeagueWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val league = context.app.settings.current().selectedLeague
        val widgets = context.app.widgets
        if (widgets.league.value == null && league != null) {
            // Nothing saved yet (a fresh install, or the app has not polled since): fetch the league
            // now rather than drawing an empty widget.
            context.app.hub.refreshOnce(league.leagueId)
            context.app.hub.engines.value[league.leagueId]?.state?.value?.let(LeagueSnapshot::of)?.let { widgets.publish(it) }
        }
        val empty = if (league == null) "Add your league in FFL Live" else "Couldn't load the league — open FFL Live"
        provideContent {
            val snapshot by widgets.league.collectAsState()
            GlanceTheme { Content(snapshot, empty) }
        }
    }

    @Composable
    private fun Content(s: LeagueSnapshot?, empty: String) {
        val wide = LocalSize.current.width >= WIDE.width
        val open = actionStartActivity<MainActivity>()
        if (s == null || s.matchups.isEmpty()) {
            Column(
                GlanceModifier.fillMaxSize().padding(horizontal = 12.dp).clickable(open),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(empty, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
            }
            return
        }
        val columns = Columns.measure(LocalContext.current, s.matchups)
        // No background: the scores sit straight on the wallpaper. A list's items take the taps; the
        // list itself can't.
        LazyColumn(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
            itemsIndexed(s.matchups) { index, m ->
                // A finished matchup's projections are just its scores again, and its counts are zero.
                val projections = wide && !m.final
                val counts = projections && (m.myRemaining != null || m.opponentRemaining != null)
                Column(GlanceModifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else GAP).clickable(open)) {
                    MatchupLines(m, projections, counts, columns)
                    if (m.lastPlay.isNotEmpty()) PlayLine(m.lastPlay, maxLines = 1)
                }
            }
        }
    }

    companion object {
        /** Names and scores only. */
        val NARROW = DpSize(180.dp, 110.dp)

        /** Wide enough for the projections and counts beside the scores. */
        val WIDE = DpSize(250.dp, 110.dp)

        /** Between one matchup and the next. */
        private val GAP = 8.dp
    }
}

class LeagueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LeagueWidget()
}
