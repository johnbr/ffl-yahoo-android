package io.github.johnbr.fflyahoo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.johnbr.fflyahoo.core.present.Present
import io.github.johnbr.fflyahoo.core.state.NflGameRow
import io.github.johnbr.fflyahoo.core.state.NflPlayRow
import io.github.johnbr.fflyahoo.core.state.NflSide
import io.github.johnbr.fflyahoo.core.state.ScoreBanner
import java.time.Instant
import java.time.ZoneId

/**
 * One NFL game: both clubs (abbreviation, 🏈, RZ, score) on the left, the clock and the situation on
 * the right; the field bar, the scoring banner and the last play under them. Tapping opens its plays.
 */
@Composable
fun NflGameView(
    game: NflGameRow,
    nowSeconds: Double,
    pulsed: MutableSet<String>,
    open: Boolean,
    onToggle: () -> Unit,
    plays: List<NflPlayRow>?,
    dimClubs: Boolean = false,
) {
    val zone = ZoneId.systemDefault()
    val awayScore = game.away.score
    val homeScore = game.home.score
    val awayLead = awayScore != null && homeScore != null && awayScore > homeScore
    val homeLead = awayScore != null && homeScore != null && homeScore > awayScore
    val over = game.state == "post"
    val clock = if (game.state == "pre") Present.kickoff(game.startTime, Instant.ofEpochSecond(nowSeconds.toLong()), zone).ifEmpty { "Scheduled" } else game.clockText
    val bg = if (open) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent

    Column(Modifier.fillMaxWidth().background(bg)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    NflTeamLine(game.away, awayLead, over && awayLead, dimClubs)
                    NflTeamLine(game.home, homeLead, over && homeLead, dimClubs)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(clock, fontSize = 13.sp, fontWeight = if (game.state == "in") FontWeight.Bold else FontWeight.Normal)
                    val situation = Present.situation(game)
                    if (situation.isNotEmpty()) Text(situation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Present.fieldFill(game)?.let { fill -> FieldBar(fill, (game.yardsToGoal ?: 100) <= 20) }
            Present.banner(game, nowSeconds)?.let { banner -> ScoreBannerView(game.gameId, banner, pulsed) }
            if (game.lastPlay.isNotEmpty()) {
                Text(
                    game.lastPlay,
                    Modifier.padding(top = 4.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontStyle = if (game.lastPlayProvisional) FontStyle.Italic else FontStyle.Normal,
                    color = if (game.lastPlayProvisional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                when {
                    game.playsId.isEmpty() -> Muted("No play feed for this game.")
                    plays == null -> Muted("Loading plays…")
                    plays.isEmpty() -> Muted("No plays yet.")
                    else -> plays.forEach { PlayLine(it) }
                }
            }
        }
    }
}

@Composable
private fun NflTeamLine(side: NflSide, leader: Boolean, won: Boolean, dim: Boolean) {
    val ffl = LocalFfl.current
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            side.abbr.ifEmpty { side.teamId },
            Modifier.width(44.dp),
            fontSize = 14.sp,
            fontWeight = if (leader) FontWeight.Bold else FontWeight.Normal,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (side.hasBall) Text("🏈 ", fontSize = 12.sp, modifier = Modifier.semantics { contentDescription = "has the ball" })
        if (side.redZone) Badge("RZ", ffl.redZone, filled = true)
        Spacer(Modifier.width(6.dp))
        Text(
            side.score?.toString() ?: "",
            fontSize = 14.sp,
            fontWeight = if (leader) FontWeight.Bold else FontWeight.Normal,
            color = if (won) ffl.winner else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** How far the drive has come, offence always left to right. Red inside the 20. */
@Composable
private fun FieldBar(fill: Int, redZone: Boolean) {
    val color = if (redZone) LocalFfl.current.redZone else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
            .semantics { contentDescription = "${100 - fill} yards to the end zone" },
    ) {
        Box(Modifier.fillMaxWidth(fill / 100f).fillMaxHeight().background(color))
    }
}

/**
 * TOUCHDOWN NO — white on red. It pulses the first time it is drawn (once per banner, however often
 * the screen recomposes) and holds still after; with system animations off the pulse is skipped.
 */
@Composable
private fun ScoreBannerView(gameId: String, banner: ScoreBanner, pulsed: MutableSet<String>) {
    val key = "$gameId@${banner.at}"
    val brightness = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        if (pulsed.add(key)) {
            repeat(2) {
                brightness.animateTo(1.45f, tween(400))
                brightness.animateTo(1f, tween(400))
            }
        }
    }
    Text(
        "${banner.kind} ${banner.abbr.uppercase()}",
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(lerp(LocalFfl.current.scoreFlash, Color.White, ((brightness.value - 1f) * 0.8f).coerceIn(0f, 0.4f)))
            .padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
        color = Color.White,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
    )
}

@Composable
private fun PlayLine(play: NflPlayRow) {
    val color = if (play.provisional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(Present.playWhen(play.period, play.clock), Modifier.width(64.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            val prefix = if (play.situation.isNotEmpty()) "${play.situation}  " else ""
            Text(
                prefix + play.shortText.ifEmpty { play.text },
                fontSize = 12.sp,
                fontStyle = if (play.provisional) FontStyle.Italic else FontStyle.Normal,
                color = color,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(vertical = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "12 later this week" / "15 final", with a caret that turns when open. */
@Composable
fun FoldRow(label: String, open: Boolean, onToggle: () -> Unit) {
    Text(
        "${if (open) "▾" else "▸"}  $label",
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
