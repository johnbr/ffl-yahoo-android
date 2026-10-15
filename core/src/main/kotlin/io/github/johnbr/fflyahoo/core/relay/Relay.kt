package io.github.johnbr.fflyahoo.core.relay

import io.github.johnbr.fflyahoo.core.Py

/**
 * Yahoo's **GameChannel** relay tier: pipe-delimited live feeds, parsed into the engine's shapes.
 *
 * Port of the relay half of `yahoo_redzone.py`. Everything here is pure — text in, values out, no
 * network and no clock. Fetching belongs to [io.github.johnbr.fflyahoo.core.client].
 */
object Relay {
    const val REDZONE_HOST = "https://pub-api.fantasysports.yahoo.com"
    const val RELAY_HOST = "https://relay-stream.sports.yahoo.com"

    /** Yahoo's own value for the desktop GameChannel. Only affects image sizes. */
    const val PLAYER_IMAGE_TYPE = "17"

    /** The league seed — teams, rosters, matchups and scoring settings. */
    fun redzoneUrl(leagueId: String, sport: String = "nfl"): String =
        "$REDZONE_HOST/fantasy/v3/redzone/$sport?league_id=$leagueId&format=json&player_image_type=$PLAYER_IMAGE_TYPE"

    /** One live feed: `games`, `stats`, `players` or `plays-<playsId>`. */
    fun relayUrl(feed: String, sport: String = "nfl"): String = "$RELAY_HOST/$sport/$feed.txt"

    /** Split a relay feed into fields, dropping its `#` comment header. */
    fun lines(text: String?): List<List<String>> =
        (text ?: "").lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) null else line.split("|")
        }

    /** The feed's `source_sequence_number`. Not a change detector — see the Python docstring. */
    fun sequence(text: String?): Int? {
        for (line in (text ?: "").lines()) {
            if (line.startsWith("# source_sequence_number=")) {
                return Py.intOrNull(line.substringAfter("="))
            }
        }
        return null
    }

    internal fun cell(cells: List<String>, index: Int): String = if (index < cells.size) cells[index] else ""

    internal fun num(value: String?): Double = Py.floatOrNull(value) ?: 0.0

    /** Live stat lines for every player in today's games, by Yahoo player id. */
    fun parseStats(text: String?): Map<String, Map<String, Double>> {
        val players = LinkedHashMap<String, LinkedHashMap<String, Double>>()
        for (cells in lines(text)) {
            val fields = Stats.PLAYER_ROWS[cells[0]] ?: continue
            if (cells.size < 2) continue
            val acc = players.getOrPut(cells[1]) { LinkedHashMap() }
            for ((name, index) in fields) {
                if (index < cells.size) acc[name] = num(cells[index])
            }
        }
        return players
    }

    /** Team-defence stat lines, by NFL team id. */
    fun parseDefense(text: String?): Map<String, Map<String, Double>> {
        val teams = LinkedHashMap<String, Map<String, Double>>()
        for (cells in lines(text)) {
            if (cells[0] != "f" || cells.size < 2) continue
            val raw = LinkedHashMap<String, Double>()
            for ((name, index) in Stats.DEFENSE_ROW) {
                if (index < cells.size) raw[name] = num(cells[index])
            }
            teams[cells[1]] = defenseStats(raw)
        }
        return teams
    }

    /**
     * Add the bucketed stats a D/ST is actually scored on. `gamesPlayed` gates the two zero-buckets —
     * otherwise every defence would score the shutout bonus before kickoff.
     */
    fun defenseStats(raw: Map<String, Double>): Map<String, Double> {
        val stats = LinkedHashMap(raw)
        val points = raw["pointsAllowed"] ?: 0.0
        val yards = raw["yardsAllowed"] ?: 0.0
        val played = (raw["gamesPlayed"] ?: 0.0) == 1.0
        fun flag(b: Boolean) = if (b) 1.0 else 0.0

        stats["pointsAllowed0"] = flag(points == 0.0 && played)
        for ((name, low, high) in listOf(
            Triple("pointsAllowed1through6", 1, 6),
            Triple("pointsAllowed7through13", 7, 13),
            Triple("pointsAllowed14through20", 14, 20),
            Triple("pointsAllowed21through27", 21, 27),
            Triple("pointsAllowed28through34", 28, 34),
        )) {
            stats[name] = flag(points >= low && points <= high)
        }
        stats["pointsAllowed35plus"] = flag(points >= 35)

        stats["yardsAllowedNegative"] = flag(yards < 0)
        stats["yardsAllowed0through99"] = flag(yards >= 0 && yards <= 99 && played)
        for ((name, low, high) in listOf(
            Triple("yardsAllowed100through199", 100, 199),
            Triple("yardsAllowed200through299", 200, 299),
            Triple("yardsAllowed300through399", 300, 399),
            Triple("yardsAllowed400through499", 400, 499),
        )) {
            stats[name] = flag(yards >= low && yards <= high)
        }
        stats["yardsAllowed500plus"] = flag(yards >= 500)

        stats["blkRetTds"] = (raw["blkFgRetTds"] ?: 0.0) + (raw["blkPuntRetTds"] ?: 0.0)
        return stats
    }

    /** The feed has no half-time state; it parks the second quarter on 0:00. */
    fun isHalftime(period: String?, clock: String?): Boolean =
        (period ?: "") == "2" && (clock ?: "").trim() in setOf("0:00", "00:00")

    /** Where a running game stands: `Q3 6:24`, `Halftime`, `OT 8:11`. */
    fun clockText(period: String?, clock: String?): String {
        val p = period ?: ""
        val c = (clock ?: "").trim()
        if (isHalftime(p, c)) return "Halftime"
        if (p.isEmpty()) return c
        val quarter = if (Py.isDigits(p) && p.toInt() <= 4) "Q$p" else "OT"
        return "$quarter $c".trim()
    }

    /** `"29"` -> `"Car"`. An unknown id falls back to itself. */
    fun teamAbbr(teamId: String): String = Stats.NFL_TEAMS[teamId] ?: teamId

    /** Minutes in a regulation NFL game. Overtime is deliberately not modelled. */
    const val GAME_MINUTES = 60.0

    /** How much of [game] is still to be played, as 0.0-1.0. A missing game is a full game ahead. */
    fun remainingFraction(game: GameState?): Double {
        if (game == null) return 1.0
        val state = game.state
        if (state == "pre") return 1.0
        if (state != "in" && state != "delayed") return 0.0
        val period = (if (game.period.isEmpty()) 1 else Py.intOrNull(game.period)) ?: return 1.0
        val clock = game.clock.ifEmpty { "0:00" }
        val minutesText = clock.substringBefore(":")
        val secondsText = if (":" in clock) clock.substringAfter(":") else ""
        val minutes = Py.intOrNull(minutesText) ?: return 1.0
        val seconds = if (secondsText.isEmpty()) 0 else Py.intOrNull(secondsText) ?: return 1.0
        val leftInPeriod = minutes + seconds / 60.0
        val quartersLeft = maxOf(0, 4 - period)
        val remaining = quartersLeft * 15.0 + leftInPeriod
        return maxOf(0.0, minOf(1.0, remaining / GAME_MINUTES))
    }

    /** `live = points_so_far + original_projection * fraction_of_game_remaining`. */
    fun liveProjection(points: Double?, projected: Double?, game: GameState?): Double? {
        if (projected == null) return points?.let { Py.round(it, 2) }
        return Py.round((points ?: 0.0) + projected * remainingFraction(game), 2)
    }

    /** A scalar that changes whenever a live game visibly moves. */
    fun liveSignature(games: Map<String, GameState>): String =
        uniqueGames(games)
            .filter { it.state == "in" }
            .map { "${it.gameId}:${it.period}:${it.clock}:${it.awayScore}-${it.homeScore}" }
            .sorted()
            .joinToString("|")

    private val PLAY_REF = Regex("""\[(\d+)]""")
    private const val UNKNOWN = "\u0000"
    private val PURE_TACKLE = Regex("""^tackled by \[\d+](?: and \[\d+])?$""")

    /** `{player_id: "First Last"}` for everyone who has appeared this week. */
    fun parsePlayers(text: String?): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        for (cells in lines(text)) {
            if (cells[0] != "m") continue
            val pid = cell(cells, PlayerRow.ID)
            if (pid.isEmpty()) continue
            names[pid] = listOf(cell(cells, PlayerRow.FIRST), cell(cells, PlayerRow.LAST))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }
        return names
    }

    /** Every play in one game, oldest first. */
    fun parsePlays(text: String?): List<RelayPlay> {
        val plays = ArrayList<RelayPlay>()
        for (cells in lines(text)) {
            if (cells[0] != "p") continue
            val body = cells.drop(PlayRow.TEXT_FROM).joinToString("|").trim()
            if (body.isEmpty()) continue
            val seqText = cell(cells, PlayRow.SEQUENCE)
            val sequence = (if (seqText.isEmpty()) 0 else Py.intOrNull(seqText)) ?: continue
            plays += RelayPlay(
                gameKey = cell(cells, PlayRow.GAME_KEY),
                sequence = sequence,
                period = cell(cells, PlayRow.PERIOD),
                clock = cell(cells, PlayRow.CLOCK),
                teamWithBall = cell(cells, PlayRow.TEAM_WITH_BALL),
                playerIds = PLAY_REF.findAll(body).map { it.groupValues[1] }.toList(),
                text = body,
                down = cell(cells, PlayRow.DOWN),
                distance = cell(cells, PlayRow.DISTANCE),
                yardsToGoal = cell(cells, PlayRow.YARDS_TO_GOAL),
                playType = cell(cells, PlayRow.PLAY_TYPE),
            )
        }
        return plays.sortedBy { it.sequence }
    }

    /**
     * Turn `[42654] rushed ..., tackled by [29298]` into plain English. A pure-tackle clause goes; a
     * clause naming an id the dictionary cannot resolve is dropped whole.
     */
    fun humanizePlay(text: String, names: Map<String, String>): String {
        val sentences = ArrayList<String>()
        for (sentence in text.split("|")) {
            val resolved = clauses(sentence).map { clause ->
                PLAY_REF.replace(clause) { m -> names[m.groupValues[1]] ?: UNKNOWN }
            }
            val joined = resolved.filter { UNKNOWN !in it }.joinToString(", ")
            if (joined.isNotEmpty()) sentences += joined
        }
        return sentences.joinToString(". ")
    }

    private fun clauses(sentence: String): List<String> =
        sentence.split(", ").map { it.trim() }.filter { it.isNotEmpty() && !PURE_TACKLE.matches(it) }

    /** The ids [humanizePlay] must resolve to keep every clause of [text]: everyone but a pure tackler. */
    fun playIdsNeeded(text: String): List<String> =
        text.split("|")
            .flatMap { clauses(it) }
            .flatMap { clause -> PLAY_REF.findAll(clause).map { it.groupValues[1] } }
            .distinct()

    /** Abbreviations of every club whose game is in progress. */
    fun liveClubs(games: Map<String, GameState>): Set<String> =
        games.values.filter { it.state == "in" }.flatMap { listOf(teamAbbr(it.away), teamAbbr(it.home)) }.toSet()

    /** `{club abbreviation: plays-feed id}` for games in progress. */
    fun playsFeeds(games: Map<String, GameState>): Map<String, String> {
        val feeds = LinkedHashMap<String, String>()
        for (game in games.values) {
            if (game.state != "in") continue
            for (club in listOf(game.away, game.home)) feeds[teamAbbr(club)] = game.playsId
        }
        return feeds
    }

    /** How many NFL games are in progress right now, each counted once. */
    fun activeGameCount(games: Map<String, GameState>): Int =
        games.values.filter { it.state == "in" }.map { it.gameId }.toSet().size

    /** Today's NFL games, keyed by **each** participating team's id. */
    fun parseGames(text: String?): Map<String, GameState> {
        val games = LinkedHashMap<String, GameState>()
        val rows = lines(text)
        for (cells in rows) {
            if (cells[0] != "g") continue
            val game = GameState(cells)
            games[game.away] = game
            games[game.home] = game
        }
        // Line scores come on their own rows after the game's, keyed by club.
        for (cells in rows) {
            if (cells[0] != "h") continue
            val club = cell(cells, LineRow.TEAM_ID)
            val game = games[club] ?: continue
            if (game.playsId != cell(cells, LineRow.PLAYS_ID)) continue
            val scores = cells.drop(LineRow.SCORES_FROM).map { Py.intOrNull(it) }
            if (scores.any { it == null }) continue
            game.quarters[club] = scores.map { it!! }
        }
        return games
    }

    /** How long after kickoff a game the relay still calls "scheduled" is taken to have been played. */
    const val FORGOTTEN_AFTER_SECONDS = 4 * 3600

    /** A line score with this many periods is a whole game; overtime is a fifth. */
    const val FULL_GAME_PERIODS = 4

    /**
     * Mark the games the relay has forgotten overnight as final, in place: a full line score on a game
     * still listed as scheduled, or a kickoff more than four hours ago. Returns the games it settled.
     */
    fun settleForgottenGames(games: Map<String, GameState>, now: Double): List<GameState> {
        val settled = ArrayList<GameState>()
        for (game in uniqueGames(games)) {
            if (game.state != "pre") continue
            val away = game.quarters[game.away] ?: emptyList()
            val home = game.quarters[game.home] ?: emptyList()
            val played = away.size >= FULL_GAME_PERIODS && home.size >= FULL_GAME_PERIODS
            val start = epoch(game.startTime)
            val stale = start != null && now - start >= FORGOTTEN_AFTER_SECONDS
            if (!(played || stale)) continue
            game.status = if (maxOf(away.size, home.size) > FULL_GAME_PERIODS) "FO" else "F"
            if (played) {
                game.awayScore = away.sum().toString()
                game.homeScore = home.sum().toString()
            } else {
                game.awayScore = ""
                game.homeScore = ""
            }
            settled += game
        }
        return settled
    }

    /**
     * Slack either side of the league's own week dates for a kickoff to still belong to that week.
     *
     * The league and the relay turn the week over hours apart: every week so far the league's seed said
     * the new week from just after midnight PT on Wednesday, while the relay kept the old week's games and
     * stat lines until it reset at 04:00. The new week's rosters were scored on the old week's stats for
     * those hours, and when the stat feed emptied every player "lost" his whole line at once (115 events at
     * 04:03 on 2026-10-14). The stat lines say nothing of their game, so the games feed decides: a club's
     * stats count toward the league's week only when its game falls in that week and has kicked off.
     *
     * `weekInfo` dates have no time zone, and Monday night's kickoff is already Tuesday in UTC. A day of
     * slack keeps every kickoff on the week's own dates inside and cannot reach a neighbouring week's games:
     * the weeks are separated by a Tuesday and a Wednesday with no football.
     */
    const val WEEK_SLACK_SECONDS = 86400.0

    /**
     * The kickoffs that belong to the league's week, as `[start, end)` epochs, from the seed's `weekInfo`
     * dates. Null when either is missing or unreadable, in which case every game is taken to be this week's.
     */
    fun weekWindow(start: String?, end: String?): Pair<Double, Double>? {
        fun midnight(value: String?): Double? = try {
            java.time.LocalDate.parse(value ?: return null)
                .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
        val low = midnight(start) ?: return null
        val high = midnight(end) ?: return null
        // `end` is a whole day, so the window runs to its midnight, plus the slack.
        return (low - WEEK_SLACK_SECONDS) to (high + 2 * WEEK_SLACK_SECONDS)
    }

    /**
     * Clubs whose row in the games feed is another week's game: the relay has not turned over yet, their
     * games this week are still to come, and whatever the stat feed holds for their players is last week's.
     */
    fun heldOverClubs(games: Map<String, GameState>, window: Pair<Double, Double>?): Set<String> {
        if (window == null) return emptySet()
        val (low, high) = window
        return games.filter { (_, game) ->
            val kickoff = epoch(game.startTime)?.toDouble() ?: return@filter false
            !(low <= kickoff && kickoff < high)
        }.keys
    }

    /**
     * This week's game, not started — so any stats for its clubs are not from it. The relay's own turnover
     * is not atomic either: its games feed can list the new slate a poll before the stat feed lets go.
     */
    fun yetToKickOff(game: GameState, now: Double): Boolean {
        val kickoff = epoch(game.startTime) ?: return false
        return game.state == "pre" && kickoff > now
    }

    /** The week's slate, one entry per GAME, in kickoff order (then id, for stability). */
    fun gamesInOrder(games: Map<String, GameState>): List<GameState> {
        val unique = LinkedHashMap<String, GameState>()
        for (game in games.values) unique[game.gameId] = game
        return unique.values.sortedWith(compareBy<GameState>({ Py.intOrNull(it.startTime) ?: 0 }, { it.gameId }))
    }

    /** Each game once — [parseGames] keys every game under both of its clubs. */
    private fun uniqueGames(games: Map<String, GameState>): List<GameState> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<GameState, Boolean>())
        return games.values.filter { seen.add(it) }
    }

    /** A relay timestamp cell as an int, or null — the feed leaves it 0 when unknown. */
    fun epoch(value: String?): Long? {
        val n = value?.trim()?.toLongOrNull() ?: return null
        return if (n > 0) n else null
    }
}

/** `p|` — one row per play in one game. */
internal object PlayRow {
    const val GAME_KEY = 1
    const val SEQUENCE = 2
    const val DOWN = 3
    const val DISTANCE = 4
    const val YARDS_TO_GOAL = 5
    const val TEAM_WITH_BALL = 6
    const val PERIOD = 7
    const val CLOCK = 8
    const val PLAY_TYPE = 9

    /** Everything from here on is description; a punt is two sentences in two columns. */
    const val TEXT_FROM = 11
}

/** `m|` — the player dictionary. */
internal object PlayerRow {
    const val ID = 1
    const val FIRST = 4
    const val LAST = 5
}

/** `h|<playsId>|<teamId>|q1|q2|...` — one club's line score in one game. */
internal object LineRow {
    const val PLAYS_ID = 1
    const val TEAM_ID = 2
    const val SCORES_FROM = 3
}

/** One play as Yahoo's per-game feed reports it. */
data class RelayPlay(
    val gameKey: String,
    val sequence: Int,
    val period: String,
    val clock: String,
    val teamWithBall: String,
    /** Everyone named in the description, in the order they appear. */
    val playerIds: List<String>,
    /** Raw, with ids still in brackets — see [Relay.humanizePlay]. */
    val text: String,
    val down: String = "",
    val distance: String = "",
    val yardsToGoal: String = "",
    /** `1` rush, `2` pass, `8` kickoff, `24` timeout / two-minute warning, `25` end of a period. */
    val playType: String = "",
) {
    val playId: String get() = "$gameKey.$sequence"
}
