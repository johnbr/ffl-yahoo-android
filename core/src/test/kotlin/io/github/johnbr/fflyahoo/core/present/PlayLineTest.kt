package io.github.johnbr.fflyahoo.core.present

import io.github.johnbr.fflyahoo.core.present.Present.PLAY_LINE_SECONDS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The widget's play line: up for three minutes after the play, as on the integration's my-score card. */
class PlayLineTest {
    @Test
    fun `the play line counts down from the play's own time`() {
        assertEquals(PLAY_LINE_SECONDS, Present.playLineLeft(1000.0, 1000.0))
        assertEquals(PLAY_LINE_SECONDS - 60, Present.playLineLeft(1000.0, 1060.0))
        assertEquals(1.0, Present.playLineLeft(1000.0, 1000.0 + PLAY_LINE_SECONDS - 1))
    }

    @Test
    fun `three minutes on it is gone and stays gone`() {
        assertEquals(0.0, Present.playLineLeft(1000.0, 1000.0 + PLAY_LINE_SECONDS))
        assertEquals(0.0, Present.playLineLeft(1000.0, 1000.0 + 9999))
    }

    @Test
    fun `a play with no time on it never expires`() {
        assertEquals(Double.POSITIVE_INFINITY, Present.playLineLeft(0.0, 4_000_000_000.0))
    }

    @Test
    fun `three minutes, as on the card`() {
        assertEquals(180.0, PLAY_LINE_SECONDS)
    }
}
