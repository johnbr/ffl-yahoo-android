package io.github.johnbr.fflyahoo.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/*
 * One matchup as the widgets draw it, shared by the single-matchup widget and the league's stack: two
 * team lines whose figures line up at the decimal point, then the last play centred under them.
 */

/**
 * Both teams' lines. [s] reads "me" as the first team and "opponent" as the second, which in the league's
 * stack are simply home and away.
 */
@Composable
internal fun MatchupLines(s: WidgetSnapshot, projections: Boolean, counts: Boolean, columns: Columns) {
    TeamLine(s.myName, s.myPoints, s.myProjected, s.myTrend, s.myRemaining, s.leader == "me", s.leader == "opponent", s.final, projections, counts, columns)
    TeamLine(s.opponentName, s.opponentPoints, s.opponentProjected, s.opponentTrend, s.opponentRemaining, s.leader == "opponent", s.leader == "me", s.final, projections, counts, columns)
}

/**
 * A faint, rounded backing behind the text, so it stays readable whatever the wallpaper has behind it.
 * Light behind the light theme's dark text and dark behind the dark theme's light text, and translucent
 * either way, so the wallpaper still shows through.
 */
internal fun GlanceModifier.scrim(): GlanceModifier = background(SCRIM).cornerRadius(16.dp)

/** The last play, centred under the two score lines so it doesn't read as a third team. */
@Composable
internal fun PlayLine(text: String, maxLines: Int) {
    Spacer(GlanceModifier.height(PLAY_GAP))
    Text(
        text,
        GlanceModifier.fillMaxWidth(),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = PLAY_SIZE, textAlign = TextAlign.Center),
        maxLines = maxLines,
    )
}

/**
 * One team: the name, then its projection, its points and its starters left to play. Each figure is
 * split at its decimal point into a right-aligned whole part and a left-aligned fraction, in columns as
 * wide as the widest of every team's parts, so the decimal points line up whatever the font's digit
 * widths. The count has a column of its own after the points, blank for a side the tier can't count.
 */
@Composable
private fun TeamLine(
    name: String,
    points: String,
    projected: String,
    trend: String,
    remaining: Int?,
    ahead: Boolean,
    behind: Boolean,
    final: Boolean,
    projections: Boolean,
    counts: Boolean,
    columns: Columns,
) {
    // Gold for the winner once the matchup is over — never mid-game, as in the app.
    val won = ahead && final
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (won) WINNER else GlanceTheme.colors.onSurface,
                // Bold is as heavy as a widget's text gets; the winner's name is a size up as well.
                fontSize = if (won) WINNER_NAME_SIZE else NAME_SIZE,
                fontWeight = if (ahead) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
        )
        if (projections) Figure(projected, columns.projected, trendColor(trend), PROJECTED_SIZE, FontWeight.Normal)
        // Both scores in the same weight: bold digits are wider, and would push the decimal points apart.
        val pointsColor = when {
            won -> WINNER
            behind -> GlanceTheme.colors.onSurfaceVariant
            else -> GlanceTheme.colors.onSurface
        }
        Figure(points, columns.points, pointsColor, POINTS_SIZE, FontWeight.Bold)
        if (counts) {
            Text(
                count(remaining),
                GlanceModifier.width(columns.count).padding(start = COUNT_GAP),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = COUNT_SIZE),
                maxLines = 1,
            )
        }
    }
}

/** A figure as two cells: the whole part right-aligned, then the decimal point and fraction left-aligned. */
@Composable
private fun Figure(text: String, cells: Cells, color: ColorProvider, size: TextUnit, weight: FontWeight) {
    val (whole, fraction) = split(text)
    Text(
        whole,
        GlanceModifier.width(cells.whole).padding(start = GAP),
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = TextAlign.End),
        maxLines = 1,
    )
    Text(
        fraction,
        GlanceModifier.width(cells.fraction),
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = TextAlign.Start),
        maxLines = 1,
    )
}

/** A live projection against the morning's, coloured as the app colours it: above green, below red. */
@Composable
private fun trendColor(trend: String): ColorProvider = when (trend) {
    "UP" -> UP
    "DOWN" -> DOWN
    "FLAT" -> GlanceTheme.colors.onSurface
    else -> GlanceTheme.colors.onSurfaceVariant
}

/** The widths of one figure's two cells. */
internal class Cells(val whole: Dp, val fraction: Dp)

/** The figures' columns and the count's, measured in the widget's own font at the user's font scale. */
internal class Columns(val points: Cells, val projected: Cells, val count: Dp) {
    companion object {
        /** Columns wide enough for every matchup in [matchups], so the stack's decimal points line up too. */
        fun measure(context: Context, matchups: List<WidgetSnapshot>): Columns = measure(
            context,
            matchups.flatMap { listOf(it.myPoints, it.opponentPoints) },
            matchups.flatMap { listOf(it.myProjected, it.opponentProjected) },
            matchups.flatMap { listOf(count(it.myRemaining), count(it.opponentRemaining)) },
        )

        private fun measure(context: Context, points: List<String>, projected: List<String>, counts: List<String>): Columns {
            val metrics = context.resources.displayMetrics
            fun column(texts: List<String>, size: TextUnit, bold: Boolean): Cells {
                val style = if (bold) Typeface.BOLD else Typeface.NORMAL
                val paints = listOf(Typeface.defaultFromStyle(style), widgetTypeface(context, style)).map { face ->
                    Paint().apply {
                        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size.value, metrics)
                        typeface = face
                    }
                }
                // A cell too narrow for its text is ellipsized, which turned "0.00" into "0.…": measured
                // in both fonts it may be drawn in, it is as wide as the wider of them needs.
                fun widest(parts: List<String>) =
                    (parts.maxOf { part -> paints.maxOf { it.measureText(part) } } / metrics.density).dp
                val split = texts.ifEmpty { listOf("") }.map(::split)
                // A little slack on each cell for rounding; the gap goes before the whole part.
                return Cells(widest(split.map { it.first }) + GAP + 2.dp, widest(split.map { it.second }) + 2.dp)
            }
            // The count is one cell: the gap before it, then the widest, as the figures are.
            val count = column(counts, COUNT_SIZE, bold = false).whole - GAP + COUNT_GAP
            return Columns(column(points, POINTS_SIZE, bold = true), column(projected, PROJECTED_SIZE, bold = false), count)
        }
    }
}

/**
 * The font the home screen draws the widget's text in. Glance's text views name no font, so they
 * take the host theme's device-default text appearance — Google Sans Text on a Pixel — and not
 * this app's [Typeface.DEFAULT], which is Roboto there. Its digits are not all one width: a bold
 * `.00` is wider than Roboto's, which is what [Columns.measure] had been sizing the cell for.
 */
private fun widgetTypeface(context: Context, style: Int): Typeface {
    val attrs = context.obtainStyledAttributes(
        android.R.style.TextAppearance_DeviceDefault,
        intArrayOf(android.R.attr.fontFamily),
    )
    val family = try {
        attrs.getString(0)
    } finally {
        attrs.recycle()
    }
    return if (family.isNullOrBlank()) Typeface.defaultFromStyle(style) else Typeface.create(family, style)
}

/**
 * How tall the widgets' lines are drawn, in dp, at the user's font scale. A text view with font padding
 * (Glance's are) is as tall as its font's top-to-bottom extent, which is more than its size: a 16sp line
 * of points is over 20dp.
 */
internal class LineHeights(val team: Dp, val play: Dp) {
    companion object {
        fun measure(context: Context): LineHeights {
            val metrics = context.resources.displayMetrics
            fun height(size: TextUnit, bold: Boolean): Dp {
                val style = if (bold) Typeface.BOLD else Typeface.NORMAL
                return listOf(Typeface.defaultFromStyle(style), widgetTypeface(context, style)).maxOf { face ->
                    val paint = Paint().apply {
                        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size.value, metrics)
                        typeface = face
                    }
                    // 10% over the font's extent: drawn on the emulator, a points line measured at 21.2dp
                    // takes 22.9dp.
                    paint.fontMetrics.let { it.bottom - it.top } * 1.1f / metrics.density
                }.dp
            }
            // A team line is as tall as its tallest text: the points, or a winner's name.
            val team = maxOf(height(POINTS_SIZE, bold = true), height(WINNER_NAME_SIZE, bold = true))
            return LineHeights(team, height(PLAY_SIZE, bold = false) + PLAY_GAP)
        }
    }
}

// The app's own trend colours (ui/Theme.kt), light and dark.
private val UP = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF66BB6A))
private val DOWN = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFEF5350))
private val WINNER = ColorProvider(day = Color(0xFFB8860B), night = Color(0xFFFBC02D))
private val SCRIM = ColorProvider(day = Color(0x99FFFFFF), night = Color(0x99000000))

private val GAP = 8.dp
private val NAME_SIZE = 14.sp
private val WINNER_NAME_SIZE = 15.sp
private val PROJECTED_SIZE = 12.sp
private val POINTS_SIZE = 16.sp
private val COUNT_SIZE = 12.sp
private val COUNT_GAP = 4.dp
private val PLAY_SIZE = 12.sp
private val PLAY_GAP = 2.dp

/** `(3)`, or nothing for a side the tier can't count. */
private fun count(remaining: Int?): String = remaining?.let { "($it)" } ?: ""

/** `126.26` -> `126` and `.26`; a figure with no decimal point (the `—` placeholder) is all whole part. */
private fun split(text: String): Pair<String, String> {
    val dot = text.indexOf('.')
    return if (dot < 0) text to "" else text.substring(0, dot) to text.substring(dot)
}
