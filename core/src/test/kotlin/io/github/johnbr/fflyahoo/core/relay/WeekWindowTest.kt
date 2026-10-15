package io.github.johnbr.fflyahoo.core.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Which kickoffs belong to the league's week. The parity goldens only cover week 1's games against a
 * week-2 seed, so the edges — Monday night, and the weeks either side — are pinned here, the same as
 * `test_week_window_takes_monday_night_and_neither_neighbouring_week` in the integration.
 */
class WeekWindowTest {
    private val window = Relay.weekWindow("2026-10-15", "2026-10-19")!!

    /** 8:15 PM ET on [day], which UTC already calls the next day. */
    private fun kickoff(day: Int) = ZonedDateTime.of(2026, 10, day, 0, 15, 0, 0, ZoneOffset.UTC).toEpochSecond().toDouble()

    private fun inside(epoch: Double) = window.first <= epoch && epoch < window.second

    @Test
    fun `takes Monday night and neither neighbouring week`() {
        assertTrue(inside(kickoff(16)), "Thursday night")
        assertTrue(inside(kickoff(20)), "Monday night, already Tuesday in UTC")
        assertFalse(inside(kickoff(13)), "week 5's Monday night")
        assertFalse(inside(kickoff(23)), "week 7's Thursday night")
    }

    @Test
    fun `a seed without week dates holds nothing back`() {
        assertNull(Relay.weekWindow(null, null))
        assertNull(Relay.weekWindow("", ""))
        assertEquals(emptySet<String>(), Relay.heldOverClubs(Relay.parseGames(""), null))
    }
}
