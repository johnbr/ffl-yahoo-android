package io.github.johnbr.fflyahoo.core.plays

import io.github.johnbr.fflyahoo.core.Golden
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.relay.RelayPlay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of the diffing, feed, matching and pieces sections of `tests/test_plays.py`. */
class PlaysTest {
    private fun player(
        key: String,
        points: Double,
        name: String = "Puka Nacua",
        teamKey: String = "461.l.1.t.1",
        matchupId: String = "m1",
        slot: String = "WR",
        stats: Map<String, Double> = emptyMap(),
    ) = PlayerSnapshot(key, name, points, teamKey, matchupId, "LAR", "WR", slot, stats)

    private fun snap(week: Int, takenAt: Double, vararg players: PlayerSnapshot) =
        LeagueSnapshot(week, takenAt, players.associateBy { it.playerKey })

    private fun one(before: LeagueSnapshot, after: LeagueSnapshot): ScoringEvent = Plays.diffSnapshots(before, after).single()

    // -- diffing -------------------------------------------------------------------------------------

    @Test
    fun `a player scoring emits one event`() {
        val event = one(snap(3, 100.0, player("p1", 12.5)), snap(3, 145.0, player("p1", 18.9)))
        assertEquals("p1", event.playerKey)
        assertEquals(6.4, event.delta)
        assertEquals("6.4", event.delta.toString(), "float noise must not leak into the delta")
        assertEquals(12.5, event.oldPoints)
        assertEquals(18.9, event.newPoints)
        assertFalse(event.correction)
        assertTrue(event.starter)
        assertEquals(145.0, event.timestamp)
        assertEquals(3, event.week)
    }

    @Test
    fun `events sort biggest first with corrections last`() {
        val events = Plays.diffSnapshots(
            snap(3, 100.0, player("p1", 10.0), player("p2", 10.0, name = "Sam Darnold"), player("p3", 10.0, name = "Cam Little")),
            snap(3, 145.0, player("p1", 12.0), player("p2", 18.0, name = "Sam Darnold"), player("p3", 8.0, name = "Cam Little")),
        )
        assertEquals(listOf("p2", "p1", "p3"), events.map { it.playerKey })
        assertTrue(events.last().correction)
    }

    @Test
    fun `things that look like scoring but are not`() {
        assertTrue(Plays.diffSnapshots(null, snap(3, 100.0, player("p1", 22.4))).isEmpty(), "baseline")
        assertTrue(Plays.diffSnapshots(snap(3, 100.0, player("p1", 22.4)), snap(4, 200.0, player("p1", 0.0))).isEmpty(), "rollover")
        assertTrue(
            Plays.diffSnapshots(snap(3, 100.0, player("p1", 12.5)), snap(3, 145.0, player("p1", 12.5), player("p9", 8.4))).isEmpty(),
            "pickup",
        )
        assertTrue(
            Plays.diffSnapshots(snap(3, 100.0, player("p1", 12.5), player("p9", 8.4)), snap(3, 145.0, player("p1", 12.5))).isEmpty(),
            "drop",
        )
        assertTrue(Plays.diffSnapshots(snap(3, 100.0, player("p1", 12.5)), snap(3, 145.0, player("p1", 12.5, slot = "BN"))).isEmpty(), "lineup")
        assertTrue(Plays.diffSnapshots(snap(3, 100.0, player("p1", 12.50)), snap(3, 145.0, player("p1", 12.53))).isEmpty(), "jitter")
    }

    @Test
    fun `bench and starter flags follow the slot now`() {
        assertTrue(one(snap(3, 100.0, player("p1", 12.5, slot = "BN")), snap(3, 145.0, player("p1", 18.9, slot = "WR"))).starter)
        assertFalse(one(snap(3, 100.0, player("p1", 12.5, slot = "BN")), snap(3, 145.0, player("p1", 18.9, slot = "BN"))).starter)
    }

    @Test
    fun `a negative delta with no stat line is a correction, described as one`() {
        val event = one(snap(3, 100.0, player("p1", 18.9)), snap(3, 145.0, player("p1", 18.6)))
        assertTrue(event.correction)
        assertEquals(-0.3, event.delta)
        assertEquals("P. Nacua -0.30 (stat correction)", Plays.describe(event))
    }

    @Test
    fun `every negative play type survives the correction filter`() {
        val cases = mapOf(
            "interception" to (mapOf("passingYards" to 200.0) to mapOf("passingYards" to 200.0, "passingInterceptions" to 1.0)),
            "lost fumble" to (mapOf("receptions" to 3.0) to mapOf("receptions" to 3.0, "fumblesLost" to 1.0)),
            "sack" to (mapOf("sacked" to 0.0) to mapOf("sacked" to 1.0)),
            "rush for a loss" to (mapOf("rushingAttempts" to 3.0, "rushingYards" to 20.0) to mapOf("rushingAttempts" to 4.0, "rushingYards" to 17.0)),
            "reception for a loss" to (mapOf("receptions" to 2.0, "receptionYards" to 18.0) to mapOf("receptions" to 3.0, "receptionYards" to 15.0)),
        )
        for ((label, stats) in cases) {
            val event = one(snap(3, 100.0, player("p1", 20.0, stats = stats.first)), snap(3, 145.0, player("p1", 19.0, stats = stats.second)))
            assertFalse(event.correction, "$label must reach the card")
            val feed = PlayFeed()
            feed.add(listOf(event))
            assertTrue(feed.lastPlay() != null, "$label was filtered out as a correction")
        }
        val pick = one(
            snap(3, 100.0, player("p1", 18.9, stats = mapOf("passingYards" to 200.0))),
            snap(3, 145.0, player("p1", 16.9, stats = mapOf("passingYards" to 200.0, "passingInterceptions" to 1.0))),
        )
        assertTrue("Int" in pick.statDelta)
    }

    @Test
    fun `a stat walked back is still a correction`() {
        val event = one(
            snap(3, 100.0, player("p1", 18.9, stats = mapOf("receptions" to 3.0, "receptionYards" to 40.0))),
            snap(3, 145.0, player("p1", 17.4, stats = mapOf("receptions" to 2.0, "receptionYards" to 25.0))),
        )
        assertTrue(event.correction)
    }

    @Test
    fun `re-ingesting the same transition is idempotent`() {
        val before = snap(3, 100.0, player("p1", 12.5))
        val after = snap(3, 145.0, player("p1", 18.9))
        val feed = PlayFeed()
        assertEquals(1, feed.add(Plays.diffSnapshots(before, after)).size)
        assertTrue(feed.add(Plays.diffSnapshots(before, after)).isEmpty())
        assertEquals(1, feed.size)
    }

    // -- the feed ------------------------------------------------------------------------------------

    @Test
    fun `feed returns newest first and evicts without going deaf`() {
        val feed = PlayFeed()
        for (i in 0 until 3) feed.add(Plays.diffSnapshots(snap(3, 100.0 + i, player("p1", i.toDouble())), snap(3, 101.0 + i, player("p1", i + 5.0))))
        assertEquals(listOf(7.0, 6.0, 5.0), feed.recent(3).map { it.newPoints })

        val small = PlayFeed(maxlen = 2)
        for (i in 0 until 5) {
            assertEquals(1, small.add(Plays.diffSnapshots(snap(3, 100.0 + i, player("p1", i.toDouble())), snap(3, 101.0 + i, player("p1", i + 5.0)))).size)
        }
        assertEquals(2, small.size)
    }

    @Test
    fun `feed filters by matchup and team`() {
        val feed = PlayFeed()
        feed.add(
            Plays.diffSnapshots(
                snap(3, 100.0, player("p1", 0.0, matchupId = "m1", teamKey = "t1"), player("p2", 0.0, matchupId = "m2", teamKey = "t2")),
                snap(3, 145.0, player("p1", 6.0, matchupId = "m1", teamKey = "t1"), player("p2", 6.0, matchupId = "m2", teamKey = "t2")),
            ),
        )
        assertEquals(listOf("p1"), feed.recent(matchupId = "m1").map { it.playerKey })
        assertEquals(listOf("p2"), feed.recent(teamKey = "t2").map { it.playerKey })
    }

    @Test
    fun `the banner skips corrections by default`() {
        val feed = PlayFeed()
        feed.add(Plays.diffSnapshots(snap(3, 1.0, player("p1", 0.0)), snap(3, 2.0, player("p1", 6.0))))
        feed.add(Plays.diffSnapshots(snap(3, 3.0, player("p1", 6.0)), snap(3, 4.0, player("p1", 5.7))))
        assertEquals(6.0, feed.lastPlay()?.delta)
        assertTrue(feed.lastPlay(includeCorrections = true)!!.correction)
        assertNull(PlayFeed().lastPlay())
    }

    @Test
    fun `a new week clears the feed`() {
        val feed = PlayFeed()
        feed.add(Plays.diffSnapshots(snap(3, 1.0, player("p1", 0.0)), snap(3, 2.0, player("p1", 6.0))))
        feed.add(Plays.diffSnapshots(snap(4, 3.0, player("p1", 0.0)), snap(4, 4.0, player("p1", 3.0))))
        assertEquals(1, feed.size)
        assertEquals(4, feed.week)
    }

    @Test
    fun `start week clears an older week exactly once`() {
        val feed = PlayFeed.fromJson("""{"week": 1, "events": []}""")
        feed.add(Plays.diffSnapshots(snap(1, 1.0, player("p1", 0.0)), snap(1, 2.0, player("p1", 6.0))))
        assertEquals(1 to 1, feed.size to feed.week)
        assertTrue(feed.startWeek(2))
        assertEquals(0 to 2, feed.size to feed.week)
        assertFalse(feed.startWeek(2))
        feed.add(Plays.diffSnapshots(snap(2, 3.0, player("p1", 0.0)), snap(2, 4.0, player("p1", 3.0))))
        assertFalse(feed.startWeek(2))
        assertEquals(1, feed.size)

        val fresh = PlayFeed()
        assertFalse(fresh.startWeek(2))
        assertEquals(2, fresh.week)
        assertFalse(fresh.startWeek(null))
        assertEquals(2, fresh.week)
    }

    @Test
    fun `feed round trips through storage and still deduplicates`() {
        val before = snap(3, 100.0, player("p1", 12.5))
        val after = snap(3, 145.0, player("p1", 18.9))
        val feed = PlayFeed()
        feed.add(Plays.diffSnapshots(before, after).map { it.copy(play = MatchedPlay("Sam Darnold passed to Puka Nacua", 2, "9:14", "26.48"), playFloor = 57) })
        val restored = PlayFeed.fromJson(feed.toJson())
        assertEquals(1, restored.size)
        assertEquals(3, restored.week)
        assertEquals("Sam Darnold passed to Puka Nacua", restored.lastPlay()?.play?.text)
        assertEquals(57, restored.lastPlay()?.playFloor)
        assertTrue(restored.add(Plays.diffSnapshots(before, after)).isEmpty())
        assertEquals(0, PlayFeed.fromJson("not json").size)
        assertEquals(0, PlayFeed.fromJson(null).size)
    }

    // -- matching against the relay's play feed -------------------------------------------------------

    private val relay: Pair<List<RelayPlay>, Map<String, String>> by lazy {
        Relay.parsePlays(Golden.fixture("yahoo_relay_plays_26_2026_w1.txt")) to
            Relay.parsePlayers(Golden.fixture("yahoo_relay_players_2026_w1.txt"))
    }

    private fun scoringEvent(playerId: String, playFloor: Int? = null, statDelta: String = "") = ScoringEvent(
        eventId = "w1:999999.p.$playerId:0.00->1.30", week = 1, timestamp = 100.0, playerKey = "999999.p.$playerId",
        playerName = "Jaxon Smith-Njigba", teamKey = "999999.t.1", matchupId = "w1.m1", nflTeam = "Sea", position = "WR",
        delta = 1.3, oldPoints = 0.0, newPoints = 1.3, starter = true, correction = false, statDelta = statDelta, playFloor = playFloor,
    )

    @Test
    fun `a pinned revision keeps its own play instead of the newest`() {
        val (plays, names) = relay
        val mine = plays.filter { "40041" in it.playerIds }
        assertTrue(mine.size > 1)
        val pinned = mine.first().playId
        assertNotEquals(pinned, Plays.matchRelayPlay(scoringEvent("40041"), plays, names)?.playId)
        assertEquals(pinned, Plays.matchRelayPlay(scoringEvent("40041"), plays, names, pin = pinned)?.playId)
        assertNull(Plays.matchRelayPlay(scoringEvent("40041"), plays, names, pin = "nope.999"))
        assertNull(Plays.matchRelayPlay(scoringEvent("99999999"), plays, names))
        assertEquals(pinned, Plays.matchRelayPlay(scoringEvent("40041", playFloor = mine.first().sequence + 500), plays, names, pin = pinned)?.playId)
    }

    @Test
    fun `a fresh event does not take a play that predates it`() {
        val (plays, names) = relay
        val newest = plays.last { "40041" in it.playerIds }
        val before = plays.filter { it.sequence < newest.sequence }
        val event = scoringEvent("40041", playFloor = before.last().sequence)
        assertNull(Plays.matchRelayPlay(event, before, names))
        assertEquals(newest.playId, Plays.matchRelayPlay(event, plays, names)?.playId)
        assertEquals(newest.playId, Plays.matchRelayPlay(scoringEvent("40041", playFloor = null), plays, names)?.playId)
    }

    @Test
    fun `the description wins over the stat line`() {
        val (plays, names) = relay
        val event = scoringEvent("40041", statDelta = "1 Rec, 13 Rec Yds")
        assertTrue(Plays.describe(event).endsWith("1 Rec, 13 Rec Yds"))
        val described = event.copy(play = Plays.matchRelayPlay(event, plays, names))
        assertTrue("passed to Jaxon Smith-Njigba" in Plays.describe(described))
        assertEquals("J. Smith-Njigba 1 rec, 13 yds", Plays.shortDescribe(described), "the short form ignores the play")
    }

    @Test
    fun `play above floor says whether a piece has a play to wait for`() {
        val (plays, _) = relay
        val newest = plays.last { "40041" in it.playerIds }
        assertTrue(Plays.playAboveFloor(scoringEvent("40041", playFloor = newest.sequence - 1), plays))
        assertFalse(Plays.playAboveFloor(scoringEvent("40041", playFloor = newest.sequence), plays))
        assertTrue(Plays.playAboveFloor(scoringEvent("40041", playFloor = null), plays))
        assertFalse(Plays.playAboveFloor(scoringEvent("99999999", playFloor = 0), plays))
    }

    @Test
    fun `a revision replaces the text in place`() {
        val feed = PlayFeed()
        val event = scoringEvent("40041")
        feed.add(listOf(event))
        feed.revise(event.eventId, MatchedPlay("Sam Darnold complete for 13 yards"))
        assertEquals("Sam Darnold complete for 13 yards", feed.lastPlay()?.play?.text)
        feed.revise(event.eventId, MatchedPlay("Sam Darnold passed to Jaxon Smith-Njigba for 13 yard gain"))
        assertEquals(1, feed.size)
        assertEquals("Sam Darnold passed to Jaxon Smith-Njigba for 13 yard gain", feed.lastPlay()?.play?.text)
        assertNull(PlayFeed().revise("nope", null))
    }

    // -- one play, in pieces --------------------------------------------------------------------------

    private fun pinned(playId: String) = MatchedPlay("Matthew Stafford passed to Davante Adams", playId = playId)

    private fun piece(
        eventId: String,
        at: Double,
        delta: Double,
        stat: String,
        play: MatchedPlay? = null,
        playerKey: String = "802904.p.27581",
        playerName: String = "Davante Adams",
        newPoints: Double = delta,
        correction: Boolean = false,
        playFloor: Int? = null,
        oldPoints: Double = 0.0,
    ) = ScoringEvent(
        eventId, 2, at, playerKey, playerName, "802904.t.1", "w2.m1", "LAR", "WR", delta, oldPoints, newPoints, true, correction,
        play = play, statDelta = stat, playFloor = playFloor,
    )

    @Test
    fun `a play walked back by a correction is no last play`() {
        // Coleman's 3-yard catch went 0.00 -> 1.30, and the pass interference on the snap took it back.
        val feed = PlayFeed()
        feed.add(listOf(piece("e0", 90.0, 4.0, "40 Rec Yds", playerKey = "p.other", playerName = "Other Guy")))
        feed.add(listOf(piece("e1", 100.0, 1.3, "1 Rec, 3 Rec Yds")))
        feed.add(listOf(piece("e2", 110.0, -1.3, "-1 Rec, -3 Rec Yds", correction = true, oldPoints = 1.3, newPoints = 0.0)))

        assertEquals("e0", feed.lastPlay()?.eventId, "the wiped catch is not a last play")
        assertEquals(listOf("e0"), feed.recent(5).map { it.eventId })
        assertEquals(listOf("e2", "e1", "e0"), feed.recent(5, includeCorrections = true).map { it.eventId })

        feed.add(listOf(piece("e3", 120.0, 7.7, "1 Rec, 7 Rec Yds, 1 Rec TD")))
        assertEquals("e3", feed.lastPlay()?.eventId)
    }

    @Test
    fun `a partial correction leaves the play`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 7.4, "1 Rec, 64 Rec Yds")))
        feed.add(listOf(piece("e2", 110.0, -0.1, "-1 Rec Yds", correction = true, oldPoints = 7.4, newPoints = 7.3)))
        assertEquals("e1", feed.lastPlay()?.eventId)
    }

    @Test
    fun `a correction undoes only the plays it covers`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 1.3, "1 Rec, 3 Rec Yds")))
        feed.add(listOf(piece("e2", 110.0, 6.4, "4 Rec Yds, 1 Rec TD", oldPoints = 1.3, newPoints = 7.7)))
        feed.add(listOf(piece("e3", 120.0, -6.4, "-4 Rec Yds, -1 Rec TD", correction = true, oldPoints = 7.7, newPoints = 1.3)))
        assertEquals(listOf("e1"), feed.recent(5).map { it.eventId })
    }

    @Test
    fun `a later piece of the same play joins its row`() {
        val feed = PlayFeed()
        val first = piece("e1", 100.0, 1.0, "1 Rec", pinned("14.30"), newPoints = 1.0)
        assertEquals(listOf(first), feed.add(listOf(first)))
        val row = feed.add(listOf(piece("e2", 110.0, 1.9, "19 Rec Yds", pinned("14.30"), newPoints = 2.9))).single()
        assertEquals(1, feed.size)
        assertEquals("e1", row.eventId)
        assertEquals(2.9, row.delta)
        assertEquals(2.9, row.newPoints)
        assertEquals("1 Rec, 19 Rec Yds", row.statDelta)
        assertEquals(listOf("e2"), row.absorbed)
        assertEquals(row, feed.lastPlay())

        feed.add(listOf(piece("e3", 120.0, 0.1, "1 Rec Yds", pinned("14.30"))))
        val all = feed.recent(5).single()
        assertEquals(3.0, all.delta)
        assertEquals("1 Rec, 20 Rec Yds", all.statDelta)
        assertEquals(listOf("e2", "e3"), all.absorbed)
    }

    @Test
    fun `pieces of different plays, players, or corrections stay apart`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 1.0, "1 Rec", pinned("14.30"))))
        feed.add(listOf(piece("e2", 400.0, 7.4, "1 Rec, 64 Rec Yds", pinned("14.45"))))
        assertEquals(2, feed.size)
        feed.add(listOf(piece("e3", 100.0, 0.25, "1 Comp", pinned("14.30"), playerKey = "802904.p.8780", playerName = "Matthew Stafford")))
        assertEquals(3, feed.size)
        feed.add(listOf(piece("e4", 410.0, -0.1, "-1 Rec Yds", pinned("14.45"), correction = true)))
        assertEquals(4, feed.size)
    }

    @Test
    fun `a piece matched by revision joins the row it now shares`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 1.0, "1 Rec")))
        feed.add(listOf(piece("e2", 110.0, 1.9, "19 Rec Yds")))
        feed.revise("e2", pinned("14.30"))
        assertEquals(2, feed.size)
        val row = feed.revise("e1", pinned("14.30"))!!
        assertEquals(1, feed.size)
        assertEquals(Triple("e1", 2.9, listOf("e2")), Triple(row.eventId, row.delta, row.absorbed))

        val younger = PlayFeed()
        younger.add(listOf(piece("e1", 100.0, 1.0, "1 Rec", pinned("14.30"))))
        younger.add(listOf(piece("e2", 110.0, 1.9, "19 Rec Yds")))
        val merged = younger.revise("e2", pinned("14.30"))!!
        assertEquals(1, younger.size)
        assertEquals("e1" to 2.9, merged.eventId to merged.delta)
    }

    @Test
    fun `a folded piece stays folded across a restart`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 1.0, "1 Rec", pinned("14.30"))))
        feed.add(listOf(piece("e2", 110.0, 1.9, "19 Rec Yds", pinned("14.30"))))
        val restored = PlayFeed.fromJson(feed.toJson())
        assertEquals(listOf("e2"), restored.lastPlay()?.absorbed)
        assertTrue(restored.add(listOf(piece("e2", 110.0, 1.9, "19 Rec Yds", pinned("14.30")))).isEmpty())
        assertEquals(1, restored.size)
        assertEquals(2.9, restored.lastPlay()?.delta)
    }

    @Test
    fun `a piece with no play of its own folds into the last one`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 0.2, "1 Rush, 2 Rush Yds", pinned("14.58"))))
        feed.add(listOf(piece("e2", 200.0, 0.1, "1 Rush Yds", playFloor = 59)))
        val row = feed.fold("e2")!!
        assertEquals("e1", row.eventId)
        assertEquals(1, feed.size)
        assertEquals(0.3, row.delta)
        assertEquals("1 Rush, 3 Rush Yds", row.statDelta)
    }

    @Test
    fun `a piece folds past a correction in between`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 1.6, "1 Rec, 6 Rec Yds", pinned("14.53"))))
        feed.add(listOf(piece("e2", 110.0, -0.1, "-1 Rec Yds", correction = true)))
        feed.add(listOf(piece("e3", 120.0, 0.1, "1 Rec Yds")))
        assertEquals(1.7, feed.fold("e3")?.delta)
        assertEquals(listOf("e2", "e1"), feed.recent(5, includeCorrections = true).map { it.eventId })
    }

    @Test
    fun `a piece stays when nothing is known or it is too late`() {
        val unknown = PlayFeed()
        unknown.add(listOf(piece("e1", 100.0, 2.0, "1 Int", playerKey = "802904.p.100014", playerName = "Rams")))
        unknown.add(listOf(piece("e2", 110.0, 1.1, "11 ST Ret Yds", playerKey = "802904.p.100014", playerName = "Rams")))
        assertNull(unknown.fold("e2"))
        assertEquals(2, unknown.size)

        val late = PlayFeed()
        late.add(listOf(piece("e1", 100.0, 0.2, "1 Rush, 2 Rush Yds", pinned("14.58"))))
        late.add(listOf(piece("e2", 100.0 + Plays.SPLIT_TICK_SECONDS + 1, 0.1, "1 Rush Yds")))
        assertNull(late.fold("e2"))
        assertEquals(2, late.size)
    }

    @Test
    fun `fold leaves a matched event and a correction alone`() {
        val feed = PlayFeed()
        feed.add(listOf(piece("e1", 100.0, 0.2, "1 Rush, 2 Rush Yds", pinned("14.58"))))
        feed.add(listOf(piece("e2", 110.0, 0.5, "5 Rush Yds", pinned("14.60"))))
        feed.add(listOf(piece("e3", 120.0, -0.1, "-1 Rush Yds", correction = true)))
        assertNull(feed.fold("e2"))
        assertNull(feed.fold("e3"))
        assertNull(feed.fold("nope"))
        assertEquals(3, feed.size)
    }

    @Test
    fun `the feed counts every change`() {
        val feed = PlayFeed()
        assertEquals(0, feed.version)
        feed.add(listOf(piece("e1", 100.0, 1.0, "1 Rec")))
        val afterAdd = feed.version
        assertTrue(afterAdd > 0)
        feed.add(listOf(piece("e1", 100.0, 1.0, "1 Rec")))
        assertEquals(afterAdd, feed.version)
        feed.revise("e1", pinned("14.30"))
        assertTrue(feed.version > afterAdd)
        val afterRevise = feed.version
        feed.revise("nope", null)
        assertEquals(afterRevise, feed.version)
        feed.add(listOf(piece("e2", 110.0, 1.9, "19 Rec Yds", pinned("14.30"))))
        assertTrue(feed.version > afterRevise)
    }

    // -- names ---------------------------------------------------------------------------------------

    @Test
    fun `two players who abbreviate alike keep their full names, within the scope rendered`() {
        val names = mapOf("1" to "Bijan Robinson", "2" to "Brian Robinson", "3" to "Jared Goff", "4" to "Bijan Robinson")
        assertEquals(mapOf("1" to "Bijan Robinson", "2" to "Brian Robinson", "3" to "J. Goff", "4" to "Bijan Robinson"), Plays.abbreviateNames(names))
        assertEquals(mapOf("1" to "B. Robinson", "4" to "B. Robinson"), Plays.abbreviateNames(names, listOf("1", "4")), "same player, two ids")
        assertEquals(mapOf("3" to "J. Goff"), Plays.abbreviateNames(names, listOf("3", "99")), "an unknown id is left out")
    }
}
