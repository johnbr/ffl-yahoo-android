package io.github.johnbr.fflyahoo.core

import io.github.johnbr.fflyahoo.core.Golden.assertSame
import io.github.johnbr.fflyahoo.core.Golden.encode
import io.github.johnbr.fflyahoo.core.client.ConditionalFetcher
import io.github.johnbr.fflyahoo.core.client.Fetcher
import io.github.johnbr.fflyahoo.core.client.LeagueSource
import io.github.johnbr.fflyahoo.core.client.RelayClient
import io.github.johnbr.fflyahoo.core.client.SeedClient
import io.github.johnbr.fflyahoo.core.engine.EngineEvent
import io.github.johnbr.fflyahoo.core.engine.HistoryStore
import io.github.johnbr.fflyahoo.core.engine.LeagueEngine
import io.github.johnbr.fflyahoo.core.state.MatchupDetail
import io.github.johnbr.fflyahoo.core.state.NflGames
import io.github.johnbr.fflyahoo.core.state.NflPlayRow
import io.github.johnbr.fflyahoo.core.state.PlayRow
import io.github.johnbr.fflyahoo.core.state.Scoreboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * The engine replaying `golden/scenario.json`: 60 polls of a live game that the integration's real
 * coordinator was driven through. Every poll's scoreboard, NFL slate, scoring events, history, roster
 * and play list must match what Python produced.
 */
class ScenarioParityTest {
    private val golden = Golden.json("golden/scenario.json").jsonObject
    private val leagueId = golden.getValue("league_id").jsonPrimitive.content
    private val bodies = golden.getValue("bodies").jsonObject.mapValues { it.value.jsonPrimitive.content }

    /** Mirrors `_ScenarioServer`: plays feeds answer If-Modified-Since by when the body first appeared. */
    private class Server {
        var poll = 0
        var bodies: Map<String, String> = emptyMap()
        val firstSeen = HashMap<String, Pair<String, String>>()

        fun setPoll(index: Int, next: Map<String, String>) {
            poll = index
            bodies = next
            for ((url, body) in next) if (firstSeen[url]?.second != body) firstSeen[url] = "poll-$index" to body
        }
    }

    @Test
    fun `sixty polls of a live game match the integration`() = runBlocking {
        val server = Server()
        val fetch = Fetcher { url -> server.bodies.getValue(url) }
        val conditional = ConditionalFetcher { url, since ->
            val modified = server.firstSeen.getValue(url).first
            if (since != null && since == modified) null to since else server.bodies.getValue(url) to modified
        }
        var now = 0.0
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val history = object : HistoryStore {
            override suspend fun load(): String? = null
            override suspend fun save(json: String) {}
        }
        val engine = LeagueEngine(
            source = LeagueSource(SeedClient(fetch, leagueId), RelayClient(fetch, conditional)),
            history = history,
            clock = { now },
            scope = scope,
            leagueName = golden.getValue("league_name").jsonPrimitive.content,
            dispatcher = dispatcher,
            chaseBetweenPolls = false,
        )
        val fired = ArrayList<EngineEvent>()
        val collector = scope.launch { engine.events.collect { fired += it } }
        yield()

        val polls = golden.getValue("polls").jsonArray.map { it.jsonObject }
        val results = golden.getValue("results").jsonArray.map { it.jsonObject }
        for ((index, poll) in polls.withIndex()) {
            val expected = results[index]
            now = poll.getValue("now").jsonPrimitive.double
            server.setPoll(index, poll.getValue("bodies").jsonObject.mapValues { bodies.getValue(it.value.jsonPrimitive.content) })
            fired.clear()
            val interval = engine.refresh()
            kotlinx.coroutines.withContext(dispatcher) { yield() } // let the collector drain
            val at = "poll $index"

            assertEquals(expected.getValue("interval").jsonPrimitive.int, interval, "$at interval")
            assertSame(expected["fired"], encode(ListSerializer(PlayRow.serializer()), fired.filterNot { it.revised }.map { it.row }), "$at events")
            val ui = engine.state.value
            assertSame(expected["scoreboard"], encode(Scoreboard.serializer(), ui.scoreboard), "$at scoreboard")
            assertSame(expected["nfl"], encode(NflGames.serializer(), ui.nfl), "$at NFL slate")
            assertSame(expected["history"], encode(ListSerializer(PlayRow.serializer()), engine.playHistory(null, 200, startersOnly = false)), "$at history")
            assertSame(expected["detail_1"], encode(MatchupDetail.serializer(), engine.matchupDetail(1)), "$at roster")
            assertSame(expected["nfl_plays_26"], encode(ListSerializer(NflPlayRow.serializer()), engine.nflPlays("26", 12)), "$at play list")
        }
        collector.cancel()
        scope.cancel()
        dispatcher.close()
    }
}
