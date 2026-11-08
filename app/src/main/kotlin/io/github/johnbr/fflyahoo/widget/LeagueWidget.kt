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
 * last play for three minutes after it scores. Each matchup sits on a shaded tile of its own, so the
 * pairs stand apart. The figures line up down the whole stack, and a stack a little taller than the
 * widget gives up its gaps and some of its tiles' padding, then its play lines, to fit; one taller
 * still scrolls. Tapping a matchup opens the app on it.
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
        val title = s.leagueName.isNotEmpty()
        val room = size.height - PADDING * 2 - (if (title) lines.title else 0.dp)
        val teams = lines.team * (2 * s.matchups.size)
        val plays = lines.play * s.matchups.count { it.lastPlay.isNotEmpty() }
        fun fits(gap: Dp, pad: Dp, withPlays: Boolean) =
            teams + (if (withPlays) plays else 0.dp) + gap * (s.matchups.size - 1) + pad * (2 * s.matchups.size) <= room
        val (spacing, showPlays) = when {
            fits(GAP, TILE_PADDING, true) -> (GAP to TILE_PADDING) to true
            fits(TIGHT_GAP, TIGHT_TILE_PADDING, true) -> (TIGHT_GAP to TIGHT_TILE_PADDING) to true
            fits(TIGHT_GAP, TIGHT_TILE_PADDING, false) -> (TIGHT_GAP to TIGHT_TILE_PADDING) to false
            else -> (GAP to TILE_PADDING) to true
        }
        val (gap, pad) = spacing
        // The tiles' own inset makes up the rest of the text's usual 12dp from the widget's edge.
        Column(GlanceModifier.fillMaxSize().scrim().padding(horizontal = 12.dp - TILE_INSET, vertical = PADDING)) {
            if (title) TitleLine(s.leagueName, GlanceModifier.clickable(open))
            // A list's items take the taps; the list itself can't.
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                itemsIndexed(s.matchups) { index, m ->
                    // A finished matchup's projections are just its scores again, and its counts are zero.
                    val projections = wide && !m.final
                    val counts = projections && (m.myRemaining != null || m.opponentRemaining != null)
                    // Its lineup, in the app: Glance hands the parameter to the activity as an intent extra.
                    val tap = actionStartActivity<MainActivity>(actionParametersOf(MATCHUP to m.matchupId))
                    Column(GlanceModifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else gap)) {
                        Column(GlanceModifier.fillMaxWidth().tile().padding(horizontal = TILE_INSET, vertical = pad).clickable(tap)) {
                            MatchupLines(m, projections, counts, columns)
                            if (showPlays && m.lastPlay.isNotEmpty()) PlayLine(m.lastPlay, maxLines = 1)
                        }
                    }
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

        /** Above and below a matchup's lines, inside its tile. */
        private val TILE_PADDING = 4.dp

        /** The same, when the stack is short of room. */
        private val TIGHT_TILE_PADDING = 1.dp

        /** Either side of a matchup's lines, inside its tile. */
        private val TILE_INSET = 6.dp

        private val PADDING = 8.dp

        private val MATCHUP = ActionParameters.Key<String>(MainActivity.EXTRA_MATCHUP)
    }
}

class LeagueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LeagueWidget()
}
