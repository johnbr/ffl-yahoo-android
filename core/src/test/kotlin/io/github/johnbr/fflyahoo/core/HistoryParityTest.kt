package io.github.johnbr.fflyahoo.core

import io.github.johnbr.fflyahoo.core.Golden.assertSame
import io.github.johnbr.fflyahoo.core.Golden.encode
import io.github.johnbr.fflyahoo.core.history.History
import io.github.johnbr.fflyahoo.core.seed.Seed
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * A player's season, held to `golden/history.json` — what the integration's `history.py` produced over
 * the same ESPN captures and week-1 seed (see `tools/parity_dump.py`).
 */
class HistoryParityTest {
    private val golden = Golden.json("golden/history.json").jsonObject
    private val leagueId = "999999"
    private val seed = Seed.parse(Golden.fixture("yahoo_redzone_2026_w1.json"))
    private val players = History.parseEspnPlayers(History.parse(Golden.fixture("espn_players_2026.json")))
    private val schedule = History.parseEspnSchedule(History.parse(Golden.fixture("espn_schedule_2026.json")))
    private val stats = History.parse(Golden.fixture("espn_player_stats_2026.json"))
    private val cases = History.parse(Golden.fixture("espn_week5_vs_yahoo.json")).jsonObject.getValue("cases").jsonArray

    private fun doubles(o: JsonElement?): Map<String, Double> =
        (o as? JsonObject ?: JsonObject(emptyMap())).mapValues { (_, v) -> v.jsonPrimitive.double }

    private fun doubleMap(m: Map<String, Double>) = buildJsonObject { for ((k, v) in m) put(k, v) }

    private fun variant(label: String) = doubles(golden.getValue("unresolved").jsonObject.getValue(label).jsonObject["modifiers"])

    @Test
    fun `week five's lines score the league's way`() {
        val modifiers = variant("league")
        val unresolved = History.unresolvedStats(modifiers)
        val actual = buildJsonArray {
            for (case in cases) {
                val c = case.jsonObject
                val line = doubles(c["espn_stats"])
                val defense = c.getValue("defense").jsonPrimitive.content == "true"
                val (points, exact) = History.weekPoints(line, defense, modifiers, unresolved)
                add(
                    buildJsonObject {
                        put("player", c.getValue("player").jsonPrimitive.content)
                        put("points", points)
                        put("exact", exact)
                        put("stats", doubleMap(History.yahooStats(line, defense)))
                    },
                )
            }
        }
        assertSame(golden["week5"], actual, "week 5")
        // And the Python's numbers are Yahoo's: the golden is only worth holding to if that is so.
        for ((case, row) in cases.zip(golden.getValue("week5").jsonArray)) {
            assertEquals(case.jsonObject.getValue("yahoo_points").jsonPrimitive.double, row.jsonObject.getValue("points").jsonPrimitive.double, 0.005)
        }
    }

    @TestFactory
    fun `unresolved stats by league`(): List<DynamicTest> =
        golden.getValue("unresolved").jsonObject.map { (label, expected) ->
            DynamicTest.dynamicTest(label) {
                val modifiers = variant(label)
                val missing = History.unresolvedStats(modifiers)
                val kicks = golden.getValue("kick_lines").jsonArray.map { doubles(it) }
                val actual = buildJsonObject {
                    put("modifiers", doubleMap(modifiers))
                    put("unresolved", buildJsonArray { for (name in missing.sorted()) add(JsonPrimitive(name)) })
                    put(
                        "kicks",
                        buildJsonArray {
                            for (k in kicks) {
                                val (points, exact) = History.weekPoints(k, false, modifiers, missing)
                                add(buildJsonArray { add(JsonPrimitive(points)); add(JsonPrimitive(exact)) })
                            }
                        },
                    )
                }
                assertSame(expected, actual, "unresolved $label")
            }
        }

    @Test
    fun `every rostered player and every awkward query matches as the integration's does`() {
        val actual = buildJsonArray {
            for (query in golden.getValue("matches").jsonArray) {
                val q = query.jsonObject
                val name = q.getValue("name").jsonPrimitive.content
                val teamId = q.getValue("team_id").jsonPrimitive.content
                val position = q.getValue("position").jsonPrimitive.content
                val found = History.matchEspnPlayer(players, name, teamId, position)
                add(
                    buildJsonObject {
                        put("name", name)
                        put("team_id", teamId)
                        put("position", position)
                        found?.let { put("espn_id", it.espnId) }
                    },
                )
            }
        }
        assertSame(golden["matches"], actual, "matches")
    }

    @Test
    fun `names, seasons, schedule, weeks and projections`() {
        assertSame(
            golden["name_keys"],
            buildJsonObject { for (name in golden.getValue("name_keys").jsonObject.keys) put(name, History.nameKey(name)) },
            "name keys",
        )
        assertSame(
            golden["season_of"],
            buildJsonObject {
                for (date in golden.getValue("season_of").jsonObject.keys) {
                    History.seasonOf(buildJsonObject { put("start", date) })?.let { put(date, it) }
                }
            },
            "season_of",
        )
        assertSame(
            golden["schedule"],
            buildJsonObject {
                for ((club, weeks) in schedule) {
                    put(
                        club,
                        buildJsonObject {
                            for ((week, f) in weeks) {
                                put(
                                    week.toString(),
                                    buildJsonObject {
                                        put("week", f.week)
                                        put("opponent_id", f.opponentId)
                                        put("home", f.home)
                                        f.kickoff?.let { put("kickoff", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            },
            "schedule",
        )
        assertSame(golden["season_weeks"], buildJsonArray { for (w in History.seasonWeeks(schedule)) add(JsonPrimitive(w)) }, "season weeks")
        assertSame(golden["projections"], doubleMap(History.weekProjections(seed, leagueId)), "projections")
        for ((label, expected) in golden.getValue("weeks").jsonObject) {
            val espnId = espnIdOf(label)
            val actual = buildJsonObject {
                for ((week, line) in History.parseEspnWeeks(stats, espnId, 2026)) put(week.toString(), doubleMap(line))
            }
            assertSame(expected, actual, "weeks $label")
        }
        val urls = golden.getValue("urls").jsonObject
        assertEquals(urls.getValue("seed_week_8").jsonPrimitive.content, History.seedWeekUrl(leagueId, 8))
        assertEquals(urls.getValue("espn_players").jsonPrimitive.content, History.espnPlayersUrl(2026))
        assertEquals(urls.getValue("espn_stats").jsonPrimitive.content, History.espnStatsUrl(2026))
        assertEquals(urls.getValue("espn_schedule").jsonPrimitive.content, History.espnScheduleUrl(2026))
        // The filters are JSON; their spacing is Python's and Kotlin's own.
        assertEquals(History.parse(urls.getValue("espn_players_filter").jsonPrimitive.content), History.parse(History.ESPN_PLAYERS_FILTER))
        assertEquals(History.parse(urls.getValue("espn_stats_filter").jsonPrimitive.content), History.parse(History.espnStatsFilter(4431452)))
    }

    private fun espnIdOf(label: String): Long = mapOf(
        "maye" to 4431452L, "mahomes" to 3139477L, "walker" to 4567048L, "aubrey" to 3953687L, "texans" to -16034L,
    ).getValue(label)

    @TestFactory
    fun seasons(): List<DynamicTest> = golden.getValue("seasons").jsonObject.map { (label, value) ->
        DynamicTest.dynamicTest(label) {
            val expected = value.jsonObject
            val playerId = expected.getValue("player_id").jsonPrimitive.content
            val espnId = (expected["espn_id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.longOrNull
            val current = expected.getValue("current").jsonPrimitive.int
            val live = (expected["live"] as? JsonPrimitive)?.doubleOrNull
            val modifiers = variant(expected.getValue("modifiers").jsonPrimitive.content)
            val identity = History.playerIdentity(seed, leagueId, playerId)!!
            assertSame(
                expected["identity"],
                buildJsonObject {
                    put("player_id", identity.playerId)
                    put("name", identity.name)
                    put("team_id", identity.teamId)
                    put("position", identity.position)
                },
                "$label identity",
            )
            val weekly = History.weekProjections(seed, leagueId)
            val projections = golden.getValue("projection_weeks").jsonArray.associate { it.jsonPrimitive.int to weekly }
            val actuals = espnId?.let { History.parseEspnWeeks(stats, it, 2026) }
            val rows = History.seasonRows(
                identity, History.seasonWeeks(schedule), current, projections, actuals,
                schedule[identity.teamId] ?: emptyMap(), modifiers, live,
            )
            assertSame(expected["rows"], encode(ListSerializer(History.SeasonRow.serializer()), rows), "$label rows")
            assertSame(expected["totals"], encode(History.SeasonTotals.serializer(), History.seasonTotals(rows)), "$label totals")
        }
    }

    @Test
    fun `the golden is a real one`() {
        // Six seasons, every week of the NFL's eighteen, and the week-5 cases all there.
        assertEquals(6, golden.getValue("seasons").jsonObject.size)
        assertEquals((1..18).toList(), golden.getValue("season_weeks").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(115, (golden.getValue("week5") as JsonArray).size)
    }
}
