package io.github.johnbr.fflyahoo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.ui.MainActivity

/** Your matchup on the home screen: both scores; on a larger widget, projections and the last play. */
class MatchupWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val league = context.app.settings.current().selectedLeague
        var snapshot = WidgetSnapshot.read(context)
        if (snapshot == null && league?.teamId != null) {
            // Nothing saved yet (a fresh install, or the app has not polled since): fetch the matchup
            // now rather than drawing an empty widget.
            context.app.hub.refreshOnce(league.leagueId)
            snapshot = context.app.hub.engines.value[league.leagueId]?.state?.value?.let(WidgetSnapshot::of)
            snapshot?.let { WidgetSnapshot.write(context, it) }
        }
        val empty = when {
            league == null -> "Add your league in FFL Live"
            league.teamId == null -> "Pick your team in FFL Live"
            else -> "Couldn't load your matchup — open FFL Live"
        }
        provideContent { GlanceTheme { Content(snapshot, empty) } }
    }

    @Composable
    private fun Content(s: WidgetSnapshot?, empty: String) {
        val big = LocalSize.current.height >= MEDIUM.height
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s == null) {
                Text(empty, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
                return@Column
            }
            if (big) {
                Text(
                    s.leagueName + (s.activeGames.takeIf { it > 0 }?.let { " · $it live" } ?: ""),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(4.dp))
            }
            TeamLine(s.myName, s.myPoints, s.myProjected, s.myPoints >= s.opponentPoints, big)
            TeamLine(s.opponentName, s.opponentPoints, s.opponentProjected, s.opponentPoints > s.myPoints, big)
            if (big && s.lastPlay.isNotEmpty()) {
                Spacer(GlanceModifier.height(4.dp))
                Text(s.lastPlay, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
            }
        }
    }

    @Composable
    private fun TeamLine(name: String, points: String, projected: String, ahead: Boolean, big: Boolean) {
        val weight = if (ahead) FontWeight.Bold else FontWeight.Normal
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = weight), maxLines = 1)
            if (big) Text("$projected  ", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            Text(points, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = weight))
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 48.dp)
        val MEDIUM = DpSize(220.dp, 110.dp)
    }
}

class MatchupWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MatchupWidget()
}
