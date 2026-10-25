package io.github.johnbr.fflyahoo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.johnbr.fflyahoo.core.history.History
import io.github.johnbr.fflyahoo.core.present.Present
import io.github.johnbr.fflyahoo.core.state.MatchupDetail
import io.github.johnbr.fflyahoo.core.state.MatchupRow
import io.github.johnbr.fflyahoo.core.state.PlayRow
import io.github.johnbr.fflyahoo.core.state.PlayerRow
import io.github.johnbr.fflyahoo.core.state.TeamRow
import java.time.Instant
import java.time.ZoneId

/** Which panel a matchup row has open — one at a time, across the whole screen. */
enum class Panel { ROSTER, PLAYS, PLAYER }

@Composable
fun trendColor(trend: Present.Trend?): Color = when (trend) {
    Present.Trend.UP -> LocalFfl.current.up
    Present.Trend.DOWN -> LocalFfl.current.down
    Present.Trend.FLAT -> LocalFfl.current.flat
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * One matchup: home name, both scores, away name; under it the play that last moved THIS score, in
 * the column of the side that scored it, with both live projections between. Tapping the row opens
 * the rosters, tapping the play opens the scoring history, and tapping a player in the rosters swaps
 * them for his season.
 */
@Composable
fun MatchupRowView(
    row: MatchupRow,
    open: Panel?,
    onToggle: (Panel) -> Unit,
    detail: MatchupDetail?,
    history: List<PlayRow>?,
    single: Boolean = false,
    season: Result<History.PlayerSeason>? = null,
    onPlayer: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val expandedBg = if (open != null) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Column(Modifier.fillMaxWidth().background(expandedBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(Panel.ROSTER) }
                .padding(horizontal = 16.dp, vertical = if (single) 10.dp else 7.dp)
                .semantics {
                    contentDescription = "${row.home.name} ${Present.points(row.home.points)}, ${row.away.name} ${Present.points(row.away.points)}"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamName(row.home, row.leader == row.home.teamId, TextAlign.Start, Modifier.weight(1f))
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Score(row.home.points, row.leader == row.home.teamId, row.final)
                Text(" – ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Score(row.away.points, row.leader == row.away.teamId, row.final)
            }
            TeamName(row.away, row.leader == row.away.teamId, TextAlign.End, Modifier.weight(1f))
        }
        RowFoot(row, open == Panel.PLAYS, onPlay = { onToggle(Panel.PLAYS) })
        AnimatedVisibility(open != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                when (open) {
                    Panel.ROSTER -> {
                        // The play line is the usual way in, but it is gone once no starter is playing.
                        PanelLink("Scoring plays ›", Alignment.TopEnd) { onToggle(Panel.PLAYS) }
                        Projections(row)
                        WinBar(row)
                        if (detail == null) Loading("Loading rosters…") else Rosters(detail, onPlayer)
                    }
                    Panel.PLAYS -> {
                        PanelLink("‹ Lineup", Alignment.TopStart) { onToggle(Panel.ROSTER) }
                        if (history == null) Loading("Loading plays…") else History(history)
                    }
                    Panel.PLAYER -> SeasonView(season, onBack)
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun TeamName(team: TeamRow, leader: Boolean, align: TextAlign, modifier: Modifier) {
    // The season record follows the name, "Tesla (0-4)", held to it by a no-break space so a
    // wrapping name never leaves it alone on a line.
    val recordColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(team.name)
            team.record?.let { record ->
                withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = recordColor)) { append("\u00A0($record)") }
            }
        },
        modifier = modifier,
        textAlign = align,
        fontSize = 14.sp,
        fontWeight = if (leader) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun Score(points: Double?, leader: Boolean, final: Boolean) {
    // Bold for the side ahead, gold once that lead is a result — never mid-game.
    Text(
        Present.points(points),
        fontSize = 15.sp,
        fontWeight = if (leader) FontWeight.Bold else FontWeight.Medium,
        color = if (leader && final) LocalFfl.current.winner else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
    )
}

@Composable
private fun RowFoot(row: MatchupRow, playsOpen: Boolean, onPlay: () -> Unit) {
    val play = row.lastPlay
    val homeTrend = Present.trend(row.home.liveProjected, row.home.projected)
    val awayTrend = Present.trend(row.away.liveProjected, row.away.projected)
    if (play == null && homeTrend == null && awayTrend == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) { if (play != null && play.side != "away") RowPlay(play, playsOpen, TextAlign.Start, onPlay) }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (homeTrend != null) Text(Present.points(row.home.liveProjected), fontSize = 12.sp, color = trendColor(homeTrend))
            if (awayTrend != null) Text(Present.points(row.away.liveProjected), fontSize = 12.sp, color = trendColor(awayTrend))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
            if (play != null && play.side == "away") RowPlay(play, playsOpen, TextAlign.End, onPlay)
        }
    }
}

@Composable
private fun RowPlay(play: PlayRow, open: Boolean, align: TextAlign, onClick: () -> Unit) {
    val color = if (play.correction) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (play.shortWho.isNotEmpty() && play.shortWhat.isNotEmpty()) "${play.shortWho} · ${play.shortWhat}" else play.shortText.ifEmpty { play.text }
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (open) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics { contentDescription = "Show scoring play history. Last: $text" },
        textAlign = align,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

/** A link between a matchup's two panels, the lineup and its scoring plays. */
@Composable
private fun PanelLink(text: String, align: Alignment, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Text(
            text,
            Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Loading(text: String) {
    Text(text, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

/** "orig X" plus a coloured "proj Y" when the live projection has moved; otherwise just "proj X". */
@Composable
private fun Projections(row: MatchupRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ProjectionSide(row.home, Alignment.Start, Modifier.weight(1f))
        ProjectionSide(row.away, Alignment.End, Modifier.weight(1f))
    }
}

@Composable
private fun ProjectionSide(team: TeamRow, align: Alignment.Horizontal, modifier: Modifier) {
    val live = Present.hasLive(team.liveProjected, team.projected)
    Column(modifier, horizontalAlignment = align) {
        Text("${if (live) "orig" else "proj"} ${Present.points(team.projected)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (live) {
            Text("proj ${Present.points(team.liveProjected)}", fontSize = 12.sp, color = trendColor(Present.trend(team.liveProjected, team.projected)))
        }
    }
}

/** Projected winner, as a split bar: this app's own estimate from the live projections. */
@Composable
private fun WinBar(row: MatchupRow) {
    val p = row.winProb ?: return
    val home = p.coerceIn(0.0, 1.0)
    val away = 1 - home
    val homeFav = home >= away
    val fav = LocalFfl.current.up
    val other = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics {
            contentDescription = "${if (homeFav) row.home.name else row.away.name} projected to win, ${Present.percent(maxOf(home, away))}"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Present.percent(home), fontSize = 12.sp, fontWeight = if (homeFav) FontWeight.Bold else FontWeight.Normal)
        Row(Modifier.weight(1f).padding(horizontal = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp))) {
            if (home > 0) Box(Modifier.weight(home.toFloat()).height(6.dp).background(if (homeFav) fav else other))
            if (away > 0) Box(Modifier.weight(away.toFloat()).height(6.dp).background(if (homeFav) other else fav))
        }
        Text(Present.percent(away), fontSize = 12.sp, fontWeight = if (homeFav) FontWeight.Normal else FontWeight.Bold)
    }
}

/** Both teams facing each other, one shared column of slot chips down the middle, paired by index. */
@Composable
fun Rosters(detail: MatchupDetail, onPlayer: (String) -> Unit = {}) {
    if (detail.sides.size < 2) {
        Loading("No roster available for this matchup yet.")
        return
    }
    Lineup(detail.sides[0].starters, detail.sides[1].starters, onPlayer)
    val benchCount = maxOf(detail.sides[0].bench.size, detail.sides[1].bench.size)
    if (benchCount > 0) {
        var benchOpen by remember { mutableStateOf(false) }
        Text(
            "${if (benchOpen) "▾" else "▸"} Bench ($benchCount)",
            Modifier.fillMaxWidth().clickable { benchOpen = !benchOpen }.padding(vertical = 6.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (benchOpen) Lineup(detail.sides[0].bench, detail.sides[1].bench, onPlayer)
    }
}

@Composable
private fun Lineup(home: List<PlayerRow>, away: List<PlayerRow>, onPlayer: (String) -> Unit) {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    for (i in 0 until maxOf(home.size, away.size)) {
        val h = home.getOrNull(i)
        val a = away.getOrNull(i)
        val slot = h?.slot?.ifEmpty { null } ?: a?.slot ?: ""
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
            PlayerBlock(h, home = true, now, zone, Modifier.weight(1f), onPlayer)
            SlotChip(slot)
            PlayerBlock(a, home = false, now, zone, Modifier.weight(1f), onPlayer)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun SlotChip(slot: String) {
    val kind = Present.slot(slot)
    val color = LocalFfl.current.slot(kind, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        slot,
        Modifier.widthIn(min = 40.dp).padding(horizontal = 4.dp, vertical = 3.dp),
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

/** A player: name (club) + badges, game line, stat line; points over projection toward the slot chip. */
@Composable
private fun PlayerBlock(player: PlayerRow?, home: Boolean, now: Instant, zone: ZoneId, modifier: Modifier, onPlayer: (String) -> Unit) {
    if (player == null) {
        Spacer(modifier)
        return
    }
    val ffl = LocalFfl.current
    val bg = if (player.gameState == "in") ffl.liveTint else Color.Transparent
    val live = Present.hasLive(player.liveProjected, player.projected)
    val proj = if (live) player.liveProjected else player.projected
    val game = if (player.gameState == "pre" && player.kickoff != null) {
        listOf(Present.kickoff(player.kickoff, now, zone), player.game).filter { it.isNotEmpty() }.joinToString(" ")
    } else {
        player.game
    }
    val pointsColor = if (player.gameState == "post") ffl.winner else MaterialTheme.colorScheme.onSurface
    val pointsWeight = if (player.gameState == "pre") FontWeight.Medium else FontWeight.Black
    val align = if (home) Alignment.Start else Alignment.End
    val textAlign = if (home) TextAlign.Start else TextAlign.End
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            // The whole block opens his season: the only target a thumb finds on a phone-width lineup.
            .clickable(onClickLabel = "Season") { onPlayer(player.playerId) }
            .background(bg)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = if (home) Arrangement.Start else Arrangement.End,
    ) {
        val text = @Composable { m: Modifier ->
            Column(m, horizontalAlignment = align) {
                PlayerName(player, textAlign)
                if (game.isNotEmpty()) {
                    Text(game, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
                }
                if (player.statLine.isNotEmpty()) {
                    Text(player.statLine, fontSize = 11.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = textAlign)
                }
            }
        }
        val numbers = @Composable {
            Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = if (home) Alignment.End else Alignment.Start) {
                Text(Present.points(player.points), fontSize = 13.sp, fontWeight = pointsWeight, color = pointsColor)
                Text(
                    Present.points(proj),
                    fontSize = 12.sp,
                    color = if (live) trendColor(Present.trend(player.liveProjected, player.projected)) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (home) {
            text(Modifier.weight(1f))
            numbers()
        } else {
            numbers()
            text(Modifier.weight(1f))
        }
    }
}

/**
 * "D. Samuel Sr. (SF)" with its badges — injury status, 🏈, RZ — on the same line, right after the
 * name, the way the card prints them. The badges are inline content of the name's own text, so a name
 * that wraps carries them along to its last line rather than leaving them a row of their own; the
 * club is joined by a no-break space so "(SF)" never strands alone.
 */
@Composable
private fun PlayerName(player: PlayerRow, textAlign: TextAlign) {
    val ffl = LocalFfl.current
    val badges = buildList {
        if (player.status.isNotEmpty()) {
            add(InlineBadge("status", player.status, if (player.status in setOf("O", "IR")) ffl.flat else MaterialTheme.colorScheme.error, filled = false))
        }
        if (player.hasBall) add(InlineBadge("ball", "🏈", Color.Unspecified, filled = false))
        if (player.redZone) add(InlineBadge("rz", "RZ", ffl.redZone, filled = true))
    }
    val text = buildAnnotatedString {
        append(player.shortName.ifEmpty { player.name })
        if (player.nflTeam.isNotEmpty()) append("\u00a0(${player.nflTeam})")
        // A no-break space before each badge: when the line is full, the break comes inside the
        // name ("D. Samuel" / "Sr. (SF) Q"), never between the club and its badge.
        badges.forEach {
            append('\u00a0')
            appendInlineContent(it.id, it.text)
        }
    }
    val inline = badges.associate { badge ->
        // Sized in ems of the 13sp name: the 10sp bold label plus its padding.
        val width = if (badge.id == "ball") 1.1f else 0.5f + 0.5f * badge.text.length
        badge.id to InlineTextContent(Placeholder(width.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (badge.id == "ball") Text("🏈", fontSize = 10.sp, lineHeight = 12.sp) else Badge(badge.text, badge.color, badge.filled)
            }
        }
    }
    Text(text, inlineContent = inline, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = textAlign)
}

private data class InlineBadge(val id: String, val text: String, val color: Color, val filled: Boolean)

@Composable
fun Badge(text: String, color: Color, filled: Boolean = false) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (filled) color else color.copy(alpha = 0.18f))
            .padding(horizontal = 3.dp),
        fontSize = 10.sp,
        // Its own line height: the inherited body style's 24sp would clip a badge drawn inline.
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold,
        color = if (filled) Color.White else color,
    )
}

/** The matchup's scoring history: player, Yahoo's sentence, signed delta. */
@Composable
fun History(plays: List<PlayRow>) {
    if (plays.isEmpty()) {
        Loading("No scoring plays recorded yet.")
        return
    }
    val ffl = LocalFfl.current
    for (p in plays) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(p.player, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(p.text, fontSize = 12.sp, color = if (p.correction) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(Present.delta(p.delta), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (p.correction) MaterialTheme.colorScheme.error else ffl.up)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

/**
 * A player's season, week by week — the opponent (or bye), Yahoo's projection, and the points — in
 * place of the lineup it was opened from. A finished week's points are ESPN's stat line under the
 * league's scoring (see History); "≈" marks one the league's scoring could not be applied to exactly,
 * and "—" a number there is none of.
 *
 * Yahoo projects only the players on a roster in the league that week, so a week he was a free agent
 * shows ESPN's projection instead, scored the same way and set in italics. A finished week's points are
 * green above the projection and red below it — the lineup's colours — and left alone level with it;
 * this week's are still running, so they stay uncoloured.
 */
@Composable
fun SeasonView(season: Result<History.PlayerSeason>?, onBack: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹ Lineup",
            Modifier.clickable(onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
        )
        season?.getOrNull()?.let { s ->
            Text(
                listOf(s.name, if (s.nflTeam.isNotEmpty()) "(${s.nflTeam})" else "", s.position).filter { it.isNotEmpty() }.joinToString(" "),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val s = when {
        season == null -> return Loading("Loading season…")
        season.isFailure -> return Loading("Couldn't load this player's season.")
        else -> season.getOrThrow()
    }
    val totals = s.totals
    Text(
        listOfNotNull(
            "${Present.points(totals.points)} pts",
            totals.average?.let { "${Present.points(it)} a week" },
            totals.projectedRemaining.takeIf { it > 0 }?.let { "${Present.points(it)} projected to come" },
        ).joinToString(" · "),
        fontSize = 12.sp,
        color = muted,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    SeasonLine("Wk", "Opp", "Proj", "Pts", header = true)
    for (row in s.rows) {
        if (row.status == "bye") {
            SeasonLine(row.week.toString(), "Bye", "", "", dim = true)
            continue
        }
        val approx = if (row.points != null && !row.exact) "≈" else ""
        val versus = if (row.status == "final") Present.trend(row.points, row.projected) else null
        SeasonLine(
            row.week.toString(),
            row.opponent,
            Present.points(row.projected),
            if (row.status == "upcoming") "" else approx + Present.points(row.points),
            live = row.status == "live",
            dim = row.status == "upcoming",
            espn = row.projectedSource == "espn",
            pointsColor = if (versus == Present.Trend.UP || versus == Present.Trend.DOWN) trendColor(versus) else null,
        )
    }
    Text("Past weeks' points: ESPN's stat lines, scored with your league's settings.", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
    if (s.rows.any { it.projectedSource == "espn" }) {
        Text("Italic projections: $SEASON_ESPN_PROJECTION", fontSize = 11.sp, color = muted)
    }
    if (!s.matched) {
        Text("ESPN has no match for this player, so past weeks show projections only.", fontSize = 11.sp, color = muted)
    }
}

private const val SEASON_ESPN_PROJECTION =
    "ESPN's, scored with your league's settings. Yahoo projects only players on a roster in your league that week."

@Composable
private fun SeasonLine(
    week: String,
    opponent: String,
    projected: String,
    points: String,
    header: Boolean = false,
    live: Boolean = false,
    dim: Boolean = false,
    espn: Boolean = false,
    pointsColor: Color? = null,
) {
    val color = if (header || dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val size = if (header) 11.sp else 13.sp
    val bg = if (live) LocalFfl.current.liveTint else Color.Transparent
    Row(Modifier.fillMaxWidth().background(bg).padding(vertical = 3.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(week, Modifier.width(32.dp), fontSize = size, color = color)
        Text(opponent, Modifier.weight(1f), fontSize = size, color = color)
        Text(
            projected,
            Modifier.width(64.dp),
            fontSize = size,
            color = color,
            textAlign = TextAlign.End,
            fontStyle = if (espn) FontStyle.Italic else FontStyle.Normal,
        )
        Text(points, Modifier.width(64.dp), fontSize = size, color = pointsColor ?: color, textAlign = TextAlign.End, fontWeight = if (header) FontWeight.Normal else FontWeight.Bold)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
