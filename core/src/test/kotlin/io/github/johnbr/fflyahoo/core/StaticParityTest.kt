package io.github.johnbr.fflyahoo.core

import io.github.johnbr.fflyahoo.core.Golden.assertSame
import io.github.johnbr.fflyahoo.core.Golden.encode
import io.github.johnbr.fflyahoo.core.plays.PlayFeed
import io.github.johnbr.fflyahoo.core.plays.Plays
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.scoring.Scoring
import io.github.johnbr.fflyahoo.core.seed.Seed
import io.github.johnbr.fflyahoo.core.state.LeagueState
import io.github.johnbr.fflyahoo.core.state.MatchupDetail
import io.github.johnbr.fflyahoo.core.state.MyTeam
import io.github.johnbr.fflyahoo.core.state.NflGames
import io.github.johnbr.fflyahoo.core.state.Scoreboard
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs

/**
 * Pure functions over the captured fixtures, held to `golden/static.json` — what the integration's own
 * Python produced for the same inputs (see `tools/parity_dump.py`).
 */
class StaticParityTest {
    private val golden = Golden.json("golden/static.json").jsonObject
    private val leagueId = golden.getValue("league_id").jsonPrimitive.content
    private val seed = Seed.parse(Golden.fixture("yahoo_redzone_2026_w1.json"))
    private val statsText = Golden.fixture("yahoo_relay_stats_2026_w1.txt")
    private val leagueName = Seed.leagueName(seed, leagueId)

    private fun strings(map: Map<String, String>) = encode(MapSerializer(String.serializer(), String.serializer()), map)

    /** The seed saying another week, as `parity_dump.with_week_info` does; the rosters are beside the point. */
    private fun withWeekInfo(weekInfo: JsonObject?): JsonObject {
        if (weekInfo == null) return seed
        val service = seed.getValue("service").jsonObject
        val leagues = service.getValue("leagues").jsonObject
        val league = JsonObject(leagues.getValue(leagueId).jsonObject + ("weekInfo" to weekInfo))
        return JsonObject(seed + ("service" to JsonObject(service + ("leagues" to JsonObject(leagues + (leagueId to league))))))
    }

    @Test
    fun `league name, team choices and scoring modifiers`() {
        assertEquals(golden.getValue("league_name").jsonPrimitive.content, leagueName)
        assertSame(golden["team_choices"], strings(Seed.teamChoices(seed, leagueId)), "team choices")
        val modifiers = Seed.scoringModifiers(seed.getValue("service").jsonObject.getValue("leagues").jsonObject.getValue(leagueId).jsonObject)
        assertSame(golden["modifiers"], encode(MapSerializer(String.serializer(), Double.serializer()), modifiers), "modifiers")
    }

    @TestFactory
    fun leagues(): List<DynamicTest> = golden.getValue("leagues").jsonObject.map { (label, value) ->
        DynamicTest.dynamicTest(label) {
            val expected = value.jsonObject
            val gamesFile = expected["games_file"]?.jsonPrimitive?.contentOrNull
            val gamesText = gamesFile?.let(Golden::fixture) ?: ""
            val now = expected.getValue("now").jsonPrimitive.double
            val weekInfo = expected["week_info"] as? JsonObject
            val data = Seed.leagueFromPayloads(withWeekInfo(weekInfo), statsText, gamesText, leagueId, now)

            assertSame(expected["scoreboard"], encode(Scoreboard.serializer(), LeagueState.scoreboard(data, PlayFeed(), leagueId, leagueName)), "$label scoreboard")
            assertSame(expected["nfl"], encode(NflGames.serializer(), LeagueState.nflGames(data, leagueId)), "$label NFL slate")
            val details = data.matchups.indices.map { LeagueState.playerRows(data, it).copy(week = data.week) }
            assertSame(expected["details"], encode(ListSerializer(MatchupDetail.serializer()), details), "$label rosters")
            assertEquals(expected.getValue("poll_interval").jsonPrimitive.int, LeagueState.pollInterval(data))
            assertEquals(expected.getValue("poll_interval_after_10").jsonPrimitive.int, LeagueState.pollInterval(data, 10))
            assertEquals(expected.getValue("poll_interval_after_1800").jsonPrimitive.int, LeagueState.pollInterval(data, 1800))
            for ((teamId, team) in expected.getValue("my_team").jsonObject) {
                assertSame(team, LeagueState.findTeam(data, teamId)?.let { encode(MyTeam.serializer(), it) }, "$label my team $teamId")
            }
            val clubs = expected["live_clubs"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(clubs, data.liveClubs?.sorted())
            assertSame(expected["plays_feeds"], strings(data.playsFeeds), "$label plays feeds")
            assertEquals(expected.getValue("live_tick").jsonPrimitive.content, data.liveTick)
        }
    }

    @Test
    fun `stat lines, scores and deltas`() {
        val modifiers = Seed.scoringModifiers(seed.getValue("service").jsonObject.getValue("leagues").jsonObject.getValue(leagueId).jsonObject)
        val lines = Relay.parseStats(statsText).entries.map { it.key to it.value } +
            Relay.parseDefense(statsText).entries.map { "def:${it.key}" to it.value }
        val expected = golden.getValue("stat_lines").jsonArray
        assertEquals(expected.size, lines.size)
        var previousDelta = ""
        for ((row, line) in expected.zip(lines)) {
            val e = row.jsonObject
            val (key, stats) = line
            assertEquals(e.getValue("key").jsonPrimitive.content, key)
            val half = stats.mapValues { Math.floor(it.value / 2) }
            val delta = Scoring.describeDelta(half, stats)
            val back = Scoring.describeDelta(stats, half)
            val actual = mapOf(
                "score" to Scoring.score(stats, modifiers).toString(),
                "stat_line" to Scoring.statLine(stats),
                "delta" to delta,
                "delta_back" to back,
                "short" to Scoring.shortenStatDelta(delta),
                "short_back" to Scoring.shortenStatDelta(back),
                "merged" to Scoring.mergeStatDeltas(previousDelta, delta),
                "merged_back" to Scoring.mergeStatDeltas(delta, back),
            )
            for ((field, value) in actual) {
                val want = e.getValue(field).jsonPrimitive
                if (field == "score") assertEquals(want.double, value.toDouble(), 1e-9, "$key score")
                else assertEquals(want.content, value, "$key $field")
            }
            previousDelta = delta
        }
    }

    @Test
    fun `play text, ids and short names`() {
        for (set in golden.getValue("play_sets").jsonArray.map { it.jsonObject }) {
            val plays = Relay.parsePlays(Golden.fixture(set.getValue("plays_file").jsonPrimitive.content))
            val names = Relay.parsePlayers(Golden.fixture(set.getValue("players_file").jsonPrimitive.content))
            assertSame(set["names"], strings(names), "player dictionary")
            val needed = plays.flatMap { Relay.playIdsNeeded(it.text) }.toSet()
            val short = Plays.abbreviateNames(names, needed)
            assertSame(set["short_names"], strings(short), "short names")
            val expected = set.getValue("plays").jsonArray
            assertEquals(expected.size, plays.size)
            for ((e, play) in expected.map { it.jsonObject }.zip(plays)) {
                assertEquals(e.getValue("play_id").jsonPrimitive.content, play.playId)
                assertEquals(e.getValue("player_ids").jsonArray.map { it.jsonPrimitive.content }, play.playerIds)
                assertEquals(e.getValue("needed").jsonArray.map { it.jsonPrimitive.content }, Relay.playIdsNeeded(play.text))
                assertEquals(e.getValue("text").jsonPrimitive.content, Relay.humanizePlay(play.text, names), play.playId)
                assertEquals(e.getValue("short_text").jsonPrimitive.content, Relay.humanizePlay(play.text, short), play.playId)
                assertEquals(e.getValue("situation").jsonPrimitive.content, LeagueState.downAndDistance(play.down, play.distance, play.yardsToGoal))
                assertEquals(e.getValue("play_type").jsonPrimitive.content, play.playType)
            }
        }
    }

    @Test
    fun `names and league ids`() {
        for ((name, short) in golden.getValue("abbreviate").jsonObject) {
            assertEquals(short.jsonPrimitive.content, Plays.abbreviateName(name), name)
        }
        for ((raw, id) in golden.getValue("extract").jsonObject) {
            assertEquals((id as? JsonPrimitive)?.contentOrNull, Seed.extractLeagueId(raw), raw)
        }
    }

    @Test
    fun `python number formatting`() {
        for (row in golden.getValue("g").jsonArray.map { it.jsonArray }) {
            val v = row[0].jsonPrimitive.double
            assertEquals(row[1].jsonPrimitive.content, Py.g(v), "g $v")
            assertEquals(row[2].jsonPrimitive.content, Py.signedG(v), "+g $v")
            assertEquals(row[3].jsonPrimitive.content, Py.fixed(v), ".2f $v")
            assertEquals(row[4].jsonPrimitive.content, Py.signedFixed(v), "+.2f $v")
            assertEquals(row[5].jsonPrimitive.double, Py.round(v, 2), "round $v")
        }
    }

    @Test
    fun `clock, down and distance, erf`() {
        for (row in golden.getValue("clock_text").jsonArray.map { it.jsonArray }) {
            assertEquals(row[2].jsonPrimitive.content, Relay.clockText(row[0].jsonPrimitive.content, row[1].jsonPrimitive.content))
        }
        for (row in golden.getValue("down_and_distance").jsonArray.map { it.jsonArray }) {
            val (d, ds, tg) = row.take(3).map { it.jsonPrimitive.content }
            assertEquals(row[3].jsonPrimitive.content, LeagueState.downAndDistance(d, ds, tg), "$d/$ds/$tg")
        }
        for (row in golden.getValue("erf").jsonArray.map { it.jsonArray }) {
            val x = row[0].jsonPrimitive.double
            val want = row[1].jsonPrimitive.double
            val got = LeagueState.erf(x)
            assert(abs(want - got) <= 2e-15) { "erf($x) = $got, want $want" }
        }
    }
}
