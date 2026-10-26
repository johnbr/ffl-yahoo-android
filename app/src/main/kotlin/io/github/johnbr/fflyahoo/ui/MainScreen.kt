package io.github.johnbr.fflyahoo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.johnbr.fflyahoo.core.engine.LeagueEngine
import io.github.johnbr.fflyahoo.core.engine.LeagueUi
import io.github.johnbr.fflyahoo.core.present.Present
import io.github.johnbr.fflyahoo.core.state.MatchupDetail
import io.github.johnbr.fflyahoo.core.state.NflGameRow
import io.github.johnbr.fflyahoo.core.state.NflPlayRow
import io.github.johnbr.fflyahoo.core.history.History
import io.github.johnbr.fflyahoo.core.state.PlayRow
import kotlinx.coroutines.CancellationException
import io.github.johnbr.fflyahoo.data.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class Tab(val label: String) { MINE("My Matchup"), LEAGUE("League"), NFL("NFL") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(settings: AppSettings, ui: LeagueUi?, engine: LeagueEngine?, vm: MainViewModel, onSettings: () -> Unit, onAddLeague: () -> Unit) {
    val league = settings.selectedLeague ?: return
    val tabs = if (league.teamId != null) Tab.entries else listOf(Tab.LEAGUE, Tab.NFL)
    var tab by rememberSaveable(league.leagueId) { mutableStateOf(tabs.first()) }
    if (tab !in tabs) tab = tabs.first()
    // A league-widget tap: your own matchup on its tab, any other on the League tab, once the league has
    // loaded and knows which is which. Only that tab is handed the request, and it opens the matchup.
    val asked by vm.openMatchup.collectAsStateWithLifecycle()
    val target = asked?.takeIf { ui?.updatedAt != null }
        ?.let { if (Tab.MINE in tabs && ui?.myTeam?.matchupId == it) Tab.MINE else Tab.LEAGUE }
    if (target != null && tab != target) tab = target
    val focus = asked?.takeIf { target == tab }
    val focused = { vm.openMatchup.value = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { LeagueTitle(settings, ui, vm, onAddLeague) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
                tabs.forEach { t -> Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.label) }) }
            }
            ui?.error?.let { ErrorStrip(it, ui.updatedAt) }
            var refreshing by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    vm.refresh()
                    scope.launch {
                        delay(1200)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    ui == null || ui.updatedAt == null && ui.error == null -> Centered("Waiting for league data…")
                    else -> when (tab) {
                        Tab.MINE -> MyMatchupTab(ui, engine, focus, focused)
                        Tab.LEAGUE -> LeagueTab(ui, engine, focus, focused)
                        Tab.NFL -> NflTab(ui, engine)
                    }
                }
            }
        }
    }
}

@Composable
private fun LeagueTitle(settings: AppSettings, ui: LeagueUi?, vm: MainViewModel, onAddLeague: () -> Unit) {
    val league = settings.selectedLeague ?: return
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui?.scoreboard?.leagueName?.ifEmpty { null } ?: league.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch or add a league") }
            }
            ui?.scoreboard?.week?.let { Text("Week $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val live = ui?.scoreboard?.activeGames ?: 0
        if (live > 0) {
            Text(
                "$live live",
                Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).background(LocalFfl.current.live).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            settings.leagues.forEach { l ->
                DropdownMenuItem(text = { Text(l.name) }, onClick = {
                    menu = false
                    vm.select(l.leagueId)
                })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Add a league…") }, onClick = {
                menu = false
                onAddLeague()
            })
        }
    }
}

@Composable
private fun ErrorStrip(message: String, updatedAt: Double?) {
    val age = updatedAt?.let { ((System.currentTimeMillis() / 1000.0 - it) / 60).toInt() }
    Text(
        if (age != null) "Can't reach Yahoo — showing data from $age min ago." else "Can't reach Yahoo: $message",
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        fontSize = 12.sp,
    )
}

@Composable
fun Centered(text: String) {
    // A LazyColumn so pull-to-refresh still works on an empty screen.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
        item { Muted(text) }
    }
}

/** The open panel and its data, shared by the matchup tabs: one panel open at a time. */
private class MatchupPanels {
    var open by mutableStateOf<Pair<String, Panel>?>(null)
    /** The player whose season the PLAYER panel shows. */
    var player by mutableStateOf<String?>(null)
    val details = mutableStateMapOf<String, MatchupDetail>()
    val histories = mutableStateMapOf<String, List<PlayRow>>()
    val seasons = mutableStateMapOf<String, Result<History.PlayerSeason>>()

    fun toggle(matchupId: String, panel: Panel) {
        // A player's season is a view inside the lineup, so the row that closes the lineup closes it too.
        val showing = open?.let { (id, p) -> id to (if (p == Panel.PLAYER) Panel.ROSTER else p) }
        open = if (showing == matchupId to panel) null else matchupId to panel
    }

    fun openPlayer(matchupId: String, playerId: String) {
        player = playerId
        open = matchupId to Panel.PLAYER
    }
}

@Composable
private fun rememberPanels(ui: LeagueUi, engine: LeagueEngine?): MatchupPanels {
    val panels = remember(ui.leagueId) { MatchupPanels() }
    val open = panels.open
    // Fetched when opened, and again silently whenever the data moves (so an open roster's clocks
    // and points keep up). A refresh never blanks a panel that is already showing.
    LaunchedEffect(open, ui.updatedAt, ui.scoreboard.liveTick) {
        val (matchupId, panel) = open ?: return@LaunchedEffect
        val row = ui.scoreboard.matchups.firstOrNull { it.matchupId == matchupId } ?: return@LaunchedEffect
        engine ?: return@LaunchedEffect
        when (panel) {
            Panel.ROSTER -> panels.details[matchupId] = engine.matchupDetail(row.index)
            Panel.PLAYS -> panels.histories[matchupId] = engine.playHistory(matchupId)
            Panel.PLAYER -> {
                val playerId = panels.player ?: return@LaunchedEffect
                val loaded = try {
                    Result.success(engine.playerSeason(playerId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                // A failed refresh never replaces a season already on screen.
                if (loaded.isSuccess || panels.seasons[playerId]?.isSuccess != true) panels.seasons[playerId] = loaded
            }
        }
    }
    return panels
}

@Composable
private fun MyMatchupTab(ui: LeagueUi, engine: LeagueEngine?, focus: String?, onFocused: () -> Unit) {
    val panels = rememberPanels(ui, engine)
    val mine = ui.myTeam
    val row = ui.scoreboard.matchups.firstOrNull { it.matchupId == mine?.matchupId } ?: ui.scoreboard.matchups.firstOrNull()
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        if (focus == row?.matchupId) panels.open = focus to Panel.ROSTER
        onFocused()
    }
    // Under the matchup, the games its starters are playing in right now, as the NFL tab shows them.
    val live = Present.liveGamesFor(ui.nfl.games, ui.myClubs)
    val now = rememberBannerClock(live, ui.updatedAt)
    val games = rememberNflPanels(ui, engine)
    LazyColumn(Modifier.fillMaxSize()) {
        if (row == null) {
            item { Muted("Waiting for league data…", Modifier.padding(16.dp)) }
        } else {
            item {
                MatchupRowView(
                    row,
                    open = panels.open?.takeIf { it.first == row.matchupId }?.second,
                    onToggle = { panels.toggle(row.matchupId, it) },
                    detail = panels.details[row.matchupId],
                    history = panels.histories[row.matchupId],
                    single = true,
                    season = panels.player?.let { panels.seasons[it] },
                    onPlayer = { panels.openPlayer(row.matchupId, it) },
                    onBack = { panels.open = row.matchupId to Panel.ROSTER },
                )
            }
        }
        if (live.isNotEmpty()) {
            item(key = "live-heading") {
                Text(
                    "Live NFL games",
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            nflGames(live, now, games)
        }
        footer()
    }
}

@Composable
private fun LeagueTab(ui: LeagueUi, engine: LeagueEngine?, focus: String?, onFocused: () -> Unit) {
    val panels = rememberPanels(ui, engine)
    val list = rememberLazyListState()
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        val index = ui.scoreboard.matchups.indexOfFirst { it.matchupId == focus }
        // Asked for by a widget showing another league or week: nothing here to open.
        if (index >= 0) {
            panels.open = focus to Panel.ROSTER
            list.scrollToItem(index + if (ui.scoreboard.partial) 1 else 0)
        }
        onFocused()
    }
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        if (ui.scoreboard.partial) item { Muted("Some rosters could not be loaded.", Modifier.padding(horizontal = 16.dp)) }
        items(ui.scoreboard.matchups, key = { it.matchupId }) { row ->
            MatchupRowView(
                row,
                open = panels.open?.takeIf { it.first == row.matchupId }?.second,
                onToggle = { panels.toggle(row.matchupId, it) },
                detail = panels.details[row.matchupId],
                history = panels.histories[row.matchupId],
                season = panels.player?.let { panels.seasons[it] },
                onPlayer = { panels.openPlayer(row.matchupId, it) },
                onBack = { panels.open = row.matchupId to Panel.ROSTER },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        footer()
    }
}

@Composable
private fun NflTab(ui: LeagueUi, engine: LeagueEngine?) {
    val zone = ZoneId.systemDefault()
    val now = rememberBannerClock(ui.nfl.games, ui.updatedAt)
    val split = Present.split(ui.nfl.games, Instant.ofEpochSecond(now.toLong()), zone)
    var laterOpen by rememberSaveable { mutableStateOf(false) }
    var finalOpen by rememberSaveable { mutableStateOf(false) }
    val panels = rememberNflPanels(ui, engine)

    LazyColumn(Modifier.fillMaxSize()) {
        if (ui.nfl.games.isEmpty()) item { Muted("Waiting for the NFL schedule…", Modifier.padding(16.dp)) }
        nflGames(split.live, now, panels)
        nflGames(split.soon, now, panels)
        if (split.later.isNotEmpty()) {
            item(key = "fold-later") { FoldRow("${split.later.size} later this week", laterOpen) { laterOpen = !laterOpen } }
            if (laterOpen) nflGames(split.later, now, panels)
        }
        if (split.done.isNotEmpty()) {
            item(key = "fold-final") { FoldRow("${split.done.size} final", finalOpen) { finalOpen = !finalOpen } }
            if (finalOpen) nflGames(split.done, now, panels, dim = true)
        }
        footer()
    }
}

/** The clock the banners are drawn against: it ticks when the soonest one is due down, and on each poll. */
@Composable
private fun rememberBannerClock(games: List<NflGameRow>, updatedAt: Double?): Double {
    var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    // The engine publishes on polls, not on the clock, so a banner's end needs its own recomposition.
    val nextUntil = games.mapNotNull { Present.banner(it, now)?.until }.minOrNull()
    LaunchedEffect(nextUntil, updatedAt) {
        now = System.currentTimeMillis() / 1000.0
        if (nextUntil != null) {
            delay(((nextUntil - now) * 1000).toLong().coerceAtLeast(0) + 50)
            now = System.currentTimeMillis() / 1000.0
        }
    }
    return now
}

/** The open game and the plays fetched for it, shared by the lists of NFL games: one open at a time. */
private class NflPanels(private val openKey: MutableState<String?>) {
    val plays = mutableStateMapOf<String, List<NflPlayRow>>()
    /** Games whose banner has already pulsed, so a recomposition doesn't pulse it again. */
    val pulsed = mutableSetOf<String>()
    val open: String? get() = openKey.value

    private fun key(game: NflGameRow) = game.playsId.ifEmpty { "id:" + game.gameId }
    fun isOpen(game: NflGameRow) = openKey.value == key(game)
    fun toggle(game: NflGameRow) {
        openKey.value = if (isOpen(game)) null else key(game)
    }
}

@Composable
private fun rememberNflPanels(ui: LeagueUi, engine: LeagueEngine?): NflPanels {
    val openKey = rememberSaveable(ui.leagueId) { mutableStateOf<String?>(null) }
    val panels = remember(ui.leagueId) { NflPanels(openKey) }
    // The game's last-play line is a key too: play text the chase picks up between polls changes it
    // without touching the poll time or the clock, and the open list should follow it at once.
    val openLine = ui.nfl.games.firstOrNull { it.playsId == panels.open }?.let { it.lastPlay to it.lastPlayProvisional }
    LaunchedEffect(panels.open, ui.updatedAt, ui.nfl.liveTick, openLine) {
        val playsId = panels.open ?: return@LaunchedEffect
        if (playsId.startsWith("id:") || engine == null) return@LaunchedEffect
        panels.plays[playsId] = engine.nflPlays(playsId, 12)
    }
    return panels
}

private fun LazyListScope.nflGames(rows: List<NflGameRow>, now: Double, panels: NflPanels, dim: Boolean = false) {
    items(rows, key = { "g-" + it.gameId }) { game ->
        NflGameView(
            game, now, panels.pulsed,
            open = panels.isOpen(game),
            onToggle = { panels.toggle(game) },
            plays = panels.plays[game.playsId],
            dimClubs = dim,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Attribution, wherever Yahoo's data is shown. */
private fun LazyListScope.footer() {
    item(key = "footer") {
        Text(
            "Fantasy data provided by Yahoo Fantasy; past weeks' stat lines and the schedule by ESPN. " +
                "Not affiliated with, endorsed by, or sponsored by Yahoo, ESPN or the NFL.",
            Modifier.fillMaxWidth().padding(16.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
