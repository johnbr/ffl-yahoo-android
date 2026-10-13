package io.github.johnbr.fflyahoo.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.johnbr.fflyahoo.app
import io.github.johnbr.fflyahoo.ui.MainActivity

/**
 * Your matchup on the home screen. Every size shows both scores; one wide enough adds the live
 * projections, one row tall on a phone adds the last play, and a larger one the league and live count.
 */
class MatchupWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, ROW, MEDIUM))

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
        val size = LocalSize.current
        val tall = size.height >= MEDIUM.height
        val projections = size.width >= WIDE.width
        val lastPlay = size.height >= ROW.height
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(horizontal = 12.dp, vertical = if (tall) 12.dp else 8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s == null) {
                Text(empty, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
                return@Column
            }
            if (tall) {
                Text(
                    s.leagueName + (s.activeGames.takeIf { it > 0 }?.let { " · $it live" } ?: ""),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(4.dp))
            }
            val columns = Columns.measure(LocalContext.current, listOf(s.myPoints, s.opponentPoints), listOf(s.myProjected, s.opponentProjected))
            TeamLine(s.myName, s.myPoints, s.myProjected, s.myTrend, s.leader == "me", s.leader == "opponent", projections, columns)
            TeamLine(s.opponentName, s.opponentPoints, s.opponentProjected, s.opponentTrend, s.leader == "opponent", s.leader == "me", projections, columns)
            if (lastPlay && s.lastPlay.isNotEmpty()) {
                Spacer(GlanceModifier.height(if (tall) 4.dp else 2.dp))
                Text(s.lastPlay, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = if (tall) 2 else 1)
            }
        }
    }

    /**
     * One team: the name, then its projection and its points, each right-aligned in a column as wide as
     * the wider of the two teams' figures, so the decimal points line up down the widget.
     */
    @Composable
    private fun TeamLine(name: String, points: String, projected: String, trend: String, ahead: Boolean, behind: Boolean, projections: Boolean, columns: Columns) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                GlanceModifier.defaultWeight(),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = NAME_SIZE, fontWeight = if (ahead) FontWeight.Bold else FontWeight.Normal),
                maxLines = 1,
            )
            if (projections) {
                Text(
                    projected,
                    GlanceModifier.width(columns.projected).padding(start = 8.dp),
                    style = TextStyle(color = trendColor(trend), fontSize = PROJECTED_SIZE, textAlign = TextAlign.End),
                    maxLines = 1,
                )
            }
            // Both scores in the same weight: bold digits are wider, and would push the decimal points apart.
            Text(
                points,
                GlanceModifier.width(columns.points).padding(start = 8.dp),
                style = TextStyle(
                    color = if (behind) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    fontSize = POINTS_SIZE,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }

    /** A live projection against the morning's, coloured as the app colours it: above green, below red. */
    @Composable
    private fun trendColor(trend: String): ColorProvider = when (trend) {
        "UP" -> UP
        "DOWN" -> DOWN
        "FLAT" -> GlanceTheme.colors.onSurface
        else -> GlanceTheme.colors.onSurfaceVariant
    }

    /** The two number columns' widths, measured in the widget's own font at the user's font scale. */
    private class Columns(val points: Dp, val projected: Dp) {
        companion object {
            fun measure(context: Context, points: List<String>, projected: List<String>): Columns {
                val metrics = context.resources.displayMetrics
                fun width(texts: List<String>, size: TextUnit, bold: Boolean): Dp {
                    val paint = Paint().apply {
                        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size.value, metrics)
                        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    val px = texts.maxOf { paint.measureText(it) }
                    // Plus the 8dp gap before the column, and a little slack for rounding.
                    return (px / metrics.density).dp + 10.dp
                }
                return Columns(width(points, POINTS_SIZE, bold = true), width(projected, PROJECTED_SIZE, bold = false))
            }
        }
    }

    companion object {
        /** Names and scores only: a narrow widget. */
        val SMALL = DpSize(110.dp, 48.dp)

        /** Wide enough for the projections beside the scores. */
        val WIDE = DpSize(200.dp, 48.dp)

        /** One row on a phone (about 99dp): room for the last play under the scores. */
        val ROW = DpSize(200.dp, 88.dp)

        /** Two rows: the league line on top, and two lines for the last play. */
        val MEDIUM = DpSize(220.dp, 110.dp)

        // The app's own trend colours (ui/Theme.kt), light and dark.
        private val UP = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF66BB6A))
        private val DOWN = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFEF5350))

        private val NAME_SIZE = 14.sp
        private val PROJECTED_SIZE = 12.sp
        private val POINTS_SIZE = 16.sp
    }
}

class MatchupWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MatchupWidget()
}
