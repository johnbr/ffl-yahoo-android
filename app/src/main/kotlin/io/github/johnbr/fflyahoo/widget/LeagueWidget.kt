package io.github.johnbr.fflyahoo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
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
 * a little taller than the widget gives up its gaps, then its play lines, to fit; one taller still
 * scrolls. Tapping a matchup opens the app on it.
 */
class LeagueWidget : GlanceAppWidget() {
    // Exact, not Responsive: fitting the stack needs the widget's real height, not the nearest breakpoint's.
    override val sizeMode = SizeMode.Exact

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
        val size = LocalSize.current
        val wide = size.width >= WIDE
        val open = actionStartActivity<MainActivity>()
        if (s == null || s.matchups.isEmpty()) {
            Column(
                GlanceModifier.fillMaxSize().scrim().padding(horizontal = 12.dp).clickable(open),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(empty, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
            }
            return
        }
        val context = LocalContext.current
        val columns = Columns.measure(context, s.matchups)
        // A row cut in half at the bottom reads as broken, so a stack a little too tall for the widget
        // closes its gaps, and then drops its play lines, when that makes it fit. One that won't fit
        // either way keeps both and scrolls: tight gaps only make a long list harder to read.
        val lines = LineHeights.measure(context)
        val room = size.height - PADDING * 2
        val teams = lines.team * (2 * s.matchups.size)
        val plays = lines.play * s.matchups.count { it.lastPlay.isNotEmpty() }
        fun fits(gap: Dp, withPlays: Boolean) = teams + (if (withPlays) plays else 0.dp) + gap * (s.matchups.size - 1) <= room
        val (gap, showPlays) = when {
            fits(GAP, true) -> GAP to true
            fits(TIGHT_GAP, true) -> TIGHT_GAP to true
            fits(TIGHT_GAP, false) -> TIGHT_GAP to false
            else -> GAP to true
        }
        // A list's items take the taps; the list itself can't.
        LazyColumn(GlanceModifier.fillMaxSize().scrim().padding(horizontal = 12.dp, vertical = PADDING)) {
            itemsIndexed(s.matchups) { index, m ->
                // A finished matchup's projections are just its scores again, and its counts are zero.
                val projections = wide && !m.final
                val counts = projections && (m.myRemaining != null || m.opponentRemaining != null)
                // Its lineup, in the app: Glance hands the parameter to the activity as an intent extra.
                val tap = actionStartActivity<MainActivity>(actionParametersOf(MATCHUP to m.matchupId))
                Column(GlanceModifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else gap).clickable(tap)) {
                    MatchupLines(m, projections, counts, columns)
                    if (showPlays && m.lastPlay.isNotEmpty()) PlayLine(m.lastPlay, maxLines = 1)
                }
            }
        }
    }

    companion object {
        /** Wide enough for the projections and counts beside the scores. */
        private val WIDE = 250.dp

        /** Between one matchup and the next. */
        private val GAP = 8.dp

        /** The same, when the stack is short of room. */
        private val TIGHT_GAP = 2.dp

        private val PADDING = 8.dp

        private val MATCHUP = ActionParameters.Key<String>(MainActivity.EXTRA_MATCHUP)
    }
}

class LeagueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LeagueWidget()
}
