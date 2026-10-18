package io.github.johnbr.fflyahoo.core.live

import io.github.johnbr.fflyahoo.core.BUF
import io.github.johnbr.fflyahoo.core.DET
import io.github.johnbr.fflyahoo.core.gameRow
import io.github.johnbr.fflyahoo.core.relay.GameState
import io.github.johnbr.fflyahoo.core.relay.Relay
import io.github.johnbr.fflyahoo.core.state.NflPlayRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of `tests/test_pending_plays.py`. */
class PendingPlaysTest {
    private val none = Situation("", 0, 0, 0)
    private val noBall = Situation("0", 0, 0, 0)

    private fun ranFrom(game: GameState) = Pending.snapOf(game).situation

    private fun describe(before: GameState, after: GameState) =
        Pending.describeChange(Pending.snapOf(before), Pending.snapOf(after), DET, BUF)

    private fun tracker(game: GameState, newest: Landed? = null, now: Double = 0.0) =
        PendingPlays().apply { observe("2", game, newest, now) }

    // -- describeChange ------------------------------------------------------------------------------

    @Test
    fun `a gain that moves the chains says so`() =
        assertEquals("Gain of 17, 1st down", describe(gameRow(down = 2, distance = 6, toGoal = 66), gameRow(toGoal = 49)))

    @Test
    fun `a gain short of the line is just the yards`() =
        assertEquals("Gain of 4", describe(gameRow(), gameRow(down = 2, distance = 6, toGoal = 71)))

    @Test
    fun `first and ten to first and ten is a first down`() =
        assertEquals("Gain of 15, 1st down", describe(gameRow(), gameRow(toGoal = 60)))

    @Test
    fun `an incompletion is no gain`() =
        assertEquals("No gain", describe(gameRow(down = 2, distance = 6, toGoal = 66), gameRow(down = 3, distance = 6, toGoal = 66)))

    @Test
    fun `a sack is a loss`() {
        assertEquals("Loss of 8", describe(gameRow(down = 3, distance = 6, toGoal = 66), gameRow(down = 4, distance = 14, toGoal = 74)))
        // Live, 2026-10-01: the down moved, the spot had not yet.
        assertEquals("Loss of 10", describe(gameRow(down = 3, distance = 15, toGoal = 35), gameRow(down = 4, distance = 25, toGoal = 35)))
    }

    @Test
    fun `a touchdown is named for the club that scored`() {
        assertEquals("Det touchdown", describe(gameRow(down = 2, toGoal = 3), gameRow(toGoal = 0, away = 6)))
        assertEquals("Det touchdown", describe(gameRow(down = 2, toGoal = 3), gameRow(toGoal = 0, away = 7)))
    }

    @Test
    fun `kicks and tries`() {
        assertEquals("Det field goal", describe(gameRow(down = 4, toGoal = 20), gameRow(toGoal = 0, away = 3)))
        assertEquals("Det extra point", describe(gameRow(toGoal = 0, away = 6), gameRow(toGoal = 0, away = 7)))
        assertEquals("Det two-point conversion", describe(gameRow(toGoal = 0, away = 6), gameRow(toGoal = 0, away = 8)))
    }

    @Test
    fun `a safety is scored by the defence`() =
        assertEquals("Buf safety", describe(gameRow(down = 2, toGoal = 99), gameRow(toGoal = 0, home = 2)))

    @Test
    fun `losing the ball before fourth down is a turnover`() {
        assertEquals("Turnover, Buf ball", describe(gameRow(down = 2, toGoal = 60), gameRow(team = BUF, toGoal = 55)))
        assertEquals("Turnover, Buf ball", describe(gameRow(distance = 5, toGoal = 60), gameRow(team = BUF, toGoal = 55)))
    }

    @Test
    fun `a fourth down kick that flips the field is a punt`() =
        assertEquals("Punt, Buf ball", describe(gameRow(down = 4, distance = 9, toGoal = 70), gameRow(team = BUF, toGoal = 80)))

    @Test
    fun `a fourth down stop is only a change of possession`() =
        assertEquals("Change of possession, Buf ball", describe(gameRow(down = 4, distance = 1, toGoal = 38), gameRow(team = BUF, toGoal = 62)))

    @Test
    fun `nothing is said without a ball on the field`() {
        assertEquals("", describe(gameRow(toGoal = 0, away = 7), gameRow(team = BUF, toGoal = 75, away = 7)))
        assertEquals("", describe(gameRow(toGoal = 40), gameRow(team = "0", toGoal = 40)))
    }

    @Test
    fun `a re-spot is not a play`() {
        assertEquals("", describe(gameRow(down = 3, distance = 3, toGoal = 73), gameRow(down = 3, distance = 3, toGoal = 72)))
        assertEquals("", describe(gameRow(toGoal = 75), gameRow(toGoal = 74)))
    }

    @Test
    fun `a penalty replaying the down is read from the distance`() =
        assertEquals("Loss of 5", describe(gameRow(), gameRow(distance = 15, toGoal = 75)))

    @Test
    fun `a kickoff is only a change of possession`() =
        assertEquals("Change of possession, Buf ball", describe(gameRow(toGoal = 65), gameRow(team = BUF, toGoal = 99)))

    // -- PendingPlays --------------------------------------------------------------------------------

    @Test
    fun `the first look is a baseline with nothing to say`() = assertEquals(emptyMap<String, String>(), tracker(gameRow()).texts())

    @Test
    fun `a snap shows its result until its text lands`() {
        val start = gameRow(down = 2, distance = 6, toGoal = 66)
        val pending = tracker(start, newest = Landed(40, none))
        pending.observe("2", gameRow(toGoal = 49), Landed(40, none), 30.0)
        assertEquals("Gain of 17, 1st down", pending.text("2"))
        assertEquals(listOf("2"), pending.awaiting(33.0))

        pending.observe("2", gameRow(toGoal = 49), Landed(41, ranFrom(start)), 45.0)
        assertEquals("", pending.text("2"))
        assertEquals(emptyList<String>(), pending.awaiting(48.0))
    }

    @Test
    fun `a spot that moves ahead of the down waits for it`() {
        val pending = tracker(gameRow(down = 2, distance = 6, toGoal = 66))
        pending.observe("2", gameRow(down = 2, distance = 6, toGoal = 49), null, 30.0)
        assertEquals("", pending.text("2"))
        pending.observe("2", gameRow(toGoal = 49), null, 40.0)
        assertEquals("Gain of 17, 1st down", pending.text("2"))
    }

    @Test
    fun `a down that moves ahead of the spot is read from the distance`() {
        var pending = tracker(gameRow(team = BUF, toGoal = 80))
        pending.observe("2", gameRow(team = BUF, down = 2, distance = 6, toGoal = 80), null, 30.0)
        assertEquals("Gain of 4", pending.text("2"))
        pending.observe("2", gameRow(team = BUF, down = 2, distance = 5, toGoal = 75), null, 42.0)
        assertEquals("Gain of 5", pending.text("2"))

        pending = tracker(gameRow(team = BUF, down = 3, distance = 2, toGoal = 72))
        pending.observe("2", gameRow(team = BUF, toGoal = 72), null, 30.0)
        assertEquals("1st down", pending.text("2"))
        pending.observe("2", gameRow(team = BUF, toGoal = 65), null, 42.0)
        assertEquals("Gain of 7, 1st down", pending.text("2"))
    }

    @Test
    fun `a play that is undone takes its provisional line with it`() {
        val start = gameRow(team = BUF, toGoal = 65)
        val pending = tracker(start)
        pending.observe("2", gameRow(team = DET, toGoal = 76), null, 30.0)
        assertEquals("Change of possession, Det ball", pending.text("2"))
        pending.observe("2", start, null, 80.0)
        assertEquals("", pending.text("2"))
        pending.observe("2", start, Landed(41, ranFrom(start)), 86.0)
        pending.observe("2", gameRow(team = BUF, toGoal = 23), Landed(41, ranFrom(start)), 92.0)
        assertEquals("", pending.text("2"))
    }

    @Test
    fun `a touchdown whose score trails its spot is still called`() {
        val pending = tracker(gameRow(down = 2, distance = 3, toGoal = 3))
        pending.observe("2", gameRow(down = 2, distance = 3, toGoal = 0), null, 30.0)
        assertEquals("", pending.text("2"))
        pending.observe("2", gameRow(down = 2, distance = 3, toGoal = 0, away = 6), null, 45.0)
        assertEquals("Det touchdown", pending.text("2"))
    }

    @Test
    fun `no provisional when the text beat the games feed`() {
        val start = gameRow(down = 2, distance = 6, toGoal = 66)
        val pending = tracker(start, newest = Landed(40, none))
        pending.observe("2", gameRow(toGoal = 49), Landed(41, ranFrom(start)), 30.0)
        assertEquals(emptyMap<String, String>(), pending.texts())
    }

    @Test
    fun `a play landing with a games feed change is taken as its text`() {
        val pitKicks = gameRow(team = DET, toGoal = 65, clock = "10:10")
        val pending = tracker(pitKicks, newest = Landed(139, noBall, "2", "10:10"))
        val caught = gameRow(team = BUF, toGoal = 99, clock = "10:08")
        pending.observe("2", caught, Landed(139, noBall, "2", "10:10"), 150.0)
        pending.observe("2", gameRow(team = BUF, toGoal = 66, clock = "10:04"), Landed(140, noBall, "2", "10:10"), 176.0)
        assertEquals("", pending.text("2"))
    }

    @Test
    fun `two snaps awaiting text wait for the second`() {
        val first = gameRow(down = 2, distance = 3, toGoal = 3)
        val touchdown = gameRow(down = 2, distance = 3, toGoal = 0, away = 6)
        val pending = tracker(first, newest = Landed(40, none))
        pending.observe("2", touchdown, null, 30.0)
        pending.observe("2", gameRow(down = 2, distance = 3, toGoal = 0, away = 7), null, 60.0)
        assertEquals("Det extra point", pending.text("2"))
        pending.observe("2", gameRow(down = 2, distance = 3, toGoal = 0, away = 7), Landed(41, ranFrom(first)), 65.0)
        assertEquals("Det extra point", pending.text("2"))
        pending.observe("2", gameRow(down = 2, distance = 3, toGoal = 0, away = 7), Landed(42, Situation("8", 0, 0, 0)), 70.0)
        assertEquals("", pending.text("2"))
    }

    @Test
    fun `a re-read of the play feed alone clears on landing`() {
        val start = gameRow(down = 3, distance = 6, toGoal = 66)
        val after = gameRow(down = 4, distance = 6, toGoal = 66)
        val pending = tracker(start, newest = Landed(40, none))
        pending.observe("2", after, Landed(40, none), 30.0)
        pending.observe("2", after, Landed(40, none), 33.0)
        assertEquals("No gain", pending.text("2"))
        pending.observe("2", after, Landed(41, ranFrom(start)), 36.0)
        assertEquals("", pending.text("2"))
    }

    @Test
    fun `the chase gives up but the line stays`() {
        val pending = tracker(gameRow())
        pending.observe("2", gameRow(down = 2, distance = 6, toGoal = 71), null, 30.0)
        assertEquals(emptyList<String>(), pending.awaiting(30.0 + Pending.CHASE_FOR_SECONDS))
        assertEquals("Gain of 4", pending.text("2"))
    }

    @Test
    fun `a game that stops being live is forgotten`() {
        var pending = tracker(gameRow())
        pending.observe("2", gameRow(down = 2, distance = 6, toGoal = 71), null, 30.0)
        pending.observe("2", gameRow(down = 2, distance = 6, toGoal = 71, state = "post"), null, 40.0)
        assertEquals(emptyMap<String, String>(), pending.texts())

        pending = tracker(gameRow())
        pending.observe("2", gameRow(down = 2, distance = 6, toGoal = 71), null, 30.0)
        pending.forgetOthers(emptySet())
        assertEquals(emptyMap<String, String>(), pending.texts())
    }

    @Test
    fun `the list row carries the down it was run from`() {
        val pending = tracker(gameRow(down = 3, distance = 6, toGoal = 66))
        val after = gameRow(down = 4, distance = 14, toGoal = 74)
        pending.observe("2", after, null, 30.0)
        assertEquals(NflPlayRow("", "Loss of 8", "Loss of 8", "3rd & 6", "2", "", provisional = true), pending.row("2", after))
        assertNull(tracker(gameRow()).row("2", gameRow()))
    }

    @Test
    fun `a play snapped after its origin appeared is its text whatever the row says`() {
        val cle = gameRow(team = BUF, toGoal = 23, clock = "14:11")
        val pending = tracker(cle, newest = Landed(131, none, "2", "14:19"))
        pending.observe("2", gameRow(team = DET, toGoal = 77, clock = "14:11"), Landed(131, none, "2", "14:19"), 25.0)
        assertEquals("Change of possession, Det ball", pending.text("2"))
        val after = gameRow(team = DET, down = 2, distance = 3, toGoal = 70, clock = "13:54")
        pending.observe("2", after, Landed(132, ranFrom(cle), "2", "14:11"), 62.0)
        assertEquals("", pending.text("2"))
    }

    @Test
    fun `a play that lands with nothing awaited is the next change's text`() {
        val kicked = gameRow(team = BUF, toGoal = 99, clock = "10:04")
        val pending = tracker(kicked, newest = Landed(139, noBall, "2", "10:10"))
        val kickoff = Landed(140, noBall, "2", "10:10")
        pending.observe("2", kicked, kickoff, 160.0)
        pending.observe("2", gameRow(team = BUF, toGoal = 66, clock = "10:04"), kickoff, 163.0)
        assertEquals("", pending.text("2"))
        pending.observe("2", gameRow(team = BUF, down = 2, distance = 15, toGoal = 70, clock = "9:50"), kickoff, 190.0)
        assertEquals("Loss of 5", pending.text("2"))
    }

    @Test
    fun `a timeout landing does not stand in for the next snap's text`() {
        val start = gameRow(down = 2, distance = 6, toGoal = 66)
        var pending = tracker(start, newest = Landed(40, none))
        val timeout = Landed(41, noBall, "2", "9:40", "24")
        pending.observe("2", start, timeout, 30.0)
        pending.observe("2", gameRow(down = 3, distance = 6, toGoal = 66), timeout, 60.0)
        assertEquals("No gain", pending.text("2"))
        pending = tracker(start, newest = Landed(40, none))
        pending.observe("2", gameRow(down = 3, distance = 6, toGoal = 66), timeout, 30.0)
        assertEquals("No gain", pending.text("2"))
    }

    @Test
    fun `the change of hands after a score is a kickoff`() {
        val pending = tracker(gameRow(team = BUF, down = 4, distance = 2, toGoal = 26))
        pending.observe("2", gameRow(team = BUF, down = 4, distance = 2, toGoal = 26, home = 3), null, 30.0)
        assertEquals("Buf field goal", pending.text("2"))
        pending.observe("2", gameRow(team = BUF, down = 4, distance = 2, toGoal = 65, home = 3), null, 42.0)
        pending.observe("2", gameRow(team = DET, toGoal = 92, home = 3), null, 200.0)
        assertEquals("Kickoff, Det ball", pending.text("2"))
        pending.observe("2", gameRow(team = DET, toGoal = 68, home = 3), null, 212.0)
        assertEquals("Kickoff, Det ball", pending.text("2"))
        pending.observe("2", gameRow(team = DET, down = 2, distance = 4, toGoal = 62, home = 3), null, 260.0)
        pending.observe("2", gameRow(team = BUF, toGoal = 40, home = 3), null, 300.0)
        assertEquals("Turnover, Buf ball", pending.text("2"))
    }

    // -- periodEndNotReached -------------------------------------------------------------------------

    // Captured live 2026-10-18, Ari at LAR: the feed wrote "End of Half" straight after a field goal at
    // Q2 14:55, while the games feed stood at Q2 14:51.
    private val earlyHalf = Relay.parsePlays(
        "p|14|41|4|12|12|22|2|14:55|9|0|[40159] made a 30-yard field goal\n" +
            "p|14|42|1|10|65|22|2|0:00|25|0|End of Half\n",
    )
    private val at1451 = GameState(
        "g|20261018014|22|14|P|26|2|14:51|6|3|1792353900|1|10|65|22|2|3|0|0|1012|0|0|0|1|3|0|1".split("|"),
    )

    @Test
    fun `an end of half written mid quarter is held back`() {
        val (fieldGoal, endOfHalf) = earlyHalf
        assertTrue(Pending.periodEndNotReached(endOfHalf, at1451))
        assertFalse(Pending.periodEndNotReached(fieldGoal, at1451), "an ordinary play is never held back")
    }

    @Test
    fun `the end of half shows once the clock runs out`() {
        val endOfHalf = earlyHalf[1]
        assertFalse(Pending.periodEndNotReached(endOfHalf, gameRow(period = "2", clock = "0:00")))
        assertFalse(Pending.periodEndNotReached(endOfHalf, gameRow(period = "3", clock = "15:00")))
    }

    @Test
    fun `a period end a poll ahead of the games feed waits for it`() {
        val endOfFirst = Relay.parsePlays("p|9|44|4|4|63|9|1|0:00|25|0|End of 1st Quarter\n").single()
        assertTrue(Pending.periodEndNotReached(endOfFirst, gameRow(period = "1", clock = "0:17")))
        assertFalse(Pending.periodEndNotReached(endOfFirst, gameRow(period = "2", clock = "15:00")))
    }

    @Test
    fun `a clock that cannot be read holds nothing back`() {
        val endOfHalf = earlyHalf[1]
        assertFalse(Pending.periodEndNotReached(endOfHalf, gameRow(period = "", clock = "")))
        assertFalse(Pending.periodEndNotReached(endOfHalf, gameRow(period = "2", clock = "Halftime")))
    }
}
