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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.ui.MainActivity

/**
 * Your matchup on the home screen, drawn as the integration's my-score card is. Every size shows both
 * scores; one wide enough adds the live projections and, after each score, how many of that team's
 * starters still have a game to finish. One row tall on a phone adds the last play for three minutes
 * after it scores, and a larger one gives the play two lines.
 */
class MatchupWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, ROW, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val league = context.app.settings.current().selectedLeague
        val widgets = context.app.widgets
        if (widgets.snapshot.value == null && league?.teamId != null) {
            // Nothing saved yet (a fresh install, or the app has not polled since): fetch the matchup
            // now rather than drawing an empty widget.
            context.app.hub.refreshOnce(league.leagueId)
            context.app.hub.engines.value[league.leagueId]?.state?.value?.let(WidgetSnapshot::of)?.let { widgets.publish(it) }
        }
        val empty = when {
            league == null -> "Add your league in FFL Live"
            league.teamId == null -> "Pick your team in FFL Live"
            else -> "Couldn't load your matchup — open FFL Live"
        }
        provideContent {
            val snapshot by widgets.snapshot.collectAsState()
            GlanceTheme { Content(snapshot, empty) }
        }
    }

    @Composable
    private fun Content(s: WidgetSnapshot?, empty: String) {
        val size = LocalSize.current
        val tall = size.height >= MEDIUM.height
        // A finished matchup's projections are just its scores again.
        val projections = size.width >= WIDE.width && !(s?.final ?: false)
        // Like the projections: once it's over they are zero on both sides, and the result is the news.
        val counts = projections && (s?.myRemaining != null || s?.opponentRemaining != null)
        val lastPlay = size.height >= ROW.height
        Column(
            GlanceModifier
                // No background: the scores sit straight on the wallpaper.
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = if (tall) 8.dp else 4.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s == null) {
                Text(empty, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
                return@Column
            }
            MatchupLines(s, projections, counts, Columns.measure(LocalContext.current, listOf(s)))
            if (lastPlay && s.lastPlay.isNotEmpty()) PlayLine(s.lastPlay, maxLines = if (tall) 2 else 1)
        }
    }

    companion object {
        /** Names and scores only: a narrow widget. */
        val SMALL = DpSize(110.dp, 48.dp)

        /** Wide enough for the projections beside the scores. */
        val WIDE = DpSize(200.dp, 48.dp)

        /** One row on a phone (about 99dp): room for the last play under the scores. */
        val ROW = DpSize(200.dp, 88.dp)

        /** Two rows: room for two lines of the last play. */
        val MEDIUM = DpSize(220.dp, 110.dp)
    }
}

class MatchupWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MatchupWidget()
}
