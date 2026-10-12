package io.github.johnbr.fflyahoo.core.live

import io.github.johnbr.fflyahoo.core.DET
import io.github.johnbr.fflyahoo.core.gameRow
import io.github.johnbr.fflyahoo.core.relay.GameState
import io.github.johnbr.fflyahoo.core.state.ScoreBanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of `tests/test_score_banners.py`. */
class ScoreBannersTest {
    private fun game(away: Int = 0, home: Int = 0, team: String = DET, state: String = "in"): GameState =
        gameRow(team = team, away = away, home = home, state = state)

    private fun after(before: GameState, after: GameState, now: Double = 100.0): ScoreBanner? {
        val banners = ScoreBanners()
        banners.observe(listOf(before), now - 10)
        banners.observe(listOf(after), now)
        return banners.active(now)["g1"]
    }

    @Test
    fun `a jump of six to eight is a touchdown for the club that scored`() {
        for (points in 6..8) {
            val banner = after(game(17, 24), game(17 + points, 24))!!
            assertEquals("TOUCHDOWN" to "Det", banner.kind to banner.abbr)
        }
    }

    @Test
    fun `three is a field goal`() = assertEquals("FIELD GOAL", after(game(17, 24), game(17, 27))?.kind)

    @Test
    fun `two to the defence is a safety and two to the offence is only the try`() {
        assertEquals("SAFETY", after(game(17, 24, team = DET), game(17, 26))?.kind)
        assertNull(after(game(17, 24, team = DET), game(19, 24)))
        assertNull(after(game(17, 24, team = "0"), game(17, 26)), "nobody had the ball")
    }

    @Test
    fun `an extra point, a correction or a gap is no banner`() {
        assertNull(after(game(17, 24), game(18, 24)))
        assertNull(after(game(17, 24), game(14, 24)))
        assertNull(after(game(17, 24), game(27, 24)))
    }

    @Test
    fun `scores already on the board at startup are no banner`() {
        val banners = ScoreBanners()
        banners.observe(listOf(game(21, 24)), 100.0)
        assertEquals(emptyMap<String, ScoreBanner>(), banners.active(100.0))
    }

    @Test
    fun `only a game in progress raises one`() = assertNull(after(game(17, 24), game(23, 24, state = "post")))

    @Test
    fun `without a next play the banner comes down at the end of its window`() {
        val banners = ScoreBanners()
        banners.observe(listOf(game(17, 24)), 90.0)
        banners.observe(listOf(game(24, 24)), 100.0)
        assertEquals(ScoreBanner("TOUCHDOWN", "Det", 100.0, 100.0 + ScoreBanners.BANNER_SECONDS), banners.active(100.0)["g1"])
        banners.observe(listOf(game(25, 24)), 110.0)
        assertEquals(100.0, banners.active(110.0)["g1"]?.at)
        assertTrue("g1" in banners.active(100.0 + ScoreBanners.BANNER_SECONDS - 1))
        banners.observe(listOf(game(25, 24)), 100.0 + ScoreBanners.BANNER_SECONDS)
        assertEquals(emptyMap<String, ScoreBanner>(), banners.active(100.0 + ScoreBanners.BANNER_SECONDS))
    }

    private val snap = 44 to "2"
    private val td = 45 to "2"
    private val pat = 46 to "10"
    private val kickoff = 47 to "8"

    private fun touchdown(floor: Int? = 44): ScoreBanners {
        val banners = ScoreBanners()
        banners.observe(listOf(game(17, 24)), 90.0)
        banners.observe(listOf(game(23, 24)), 100.0, floor?.let { mapOf("g1" to it) } ?: emptyMap())
        return banners
    }

    @Test
    fun `the banner stays up over the scoring play and comes down on the next`() {
        val banners = touchdown()
        val later = 100.0 + ScoreBanners.MIN_BANNER_SECONDS
        assertFalse(banners.plays("g1", listOf(snap), later))
        assertFalse(banners.plays("g1", listOf(snap, td), later + 20))
        assertTrue("g1" in banners.active(later + 20))
        assertTrue(banners.plays("g1", listOf(snap, td, pat), later + 40))
        assertEquals(emptyMap<String, ScoreBanner>(), banners.active(later + 40))
    }

    @Test
    fun `a timeout between the score and the try is not the next play`() {
        val banners = touchdown()
        assertFalse(banners.plays("g1", listOf(snap, td, 46 to "24"), 130.0))
        assertTrue(banners.plays("g1", listOf(snap, td, 46 to "24", 47 to "10"), 140.0))
    }

    @Test
    fun `a next play that landed with the score still leaves it up a moment`() {
        val banners = touchdown()
        assertFalse(banners.plays("g1", listOf(snap, td, pat), 100.0))
        assertTrue("g1" in banners.active(100.0))
        assertTrue(banners.plays("g1", listOf(snap, td, pat), 100.0 + ScoreBanners.MIN_BANNER_SECONDS))
    }

    @Test
    fun `a banner raised before the feed was read takes its newest row as the score`() {
        val banners = touchdown(floor = null)
        assertFalse(banners.plays("g1", listOf(snap, td), 120.0))
        assertTrue(banners.plays("g1", listOf(snap, td, pat), 130.0))
    }

    @Test
    fun `a field goal comes down on the kickoff`() {
        val banners = ScoreBanners()
        banners.observe(listOf(game(17, 24)), 90.0)
        banners.observe(listOf(game(17, 27)), 100.0, mapOf("g1" to 44))
        assertFalse(banners.plays("g1", listOf(snap, 45 to "6"), 120.0))
        assertTrue(banners.plays("g1", listOf(snap, 45 to "6", 46 to "8"), 130.0))
    }

    @Test
    fun `a game with no banner ignores its feed`() = assertFalse(ScoreBanners().plays("g1", listOf(snap, td, pat, kickoff), 100.0))

    @Test
    fun `a new scoring play replaces the banner`() {
        val banners = ScoreBanners()
        banners.observe(listOf(game(17, 24)), 90.0)
        banners.observe(listOf(game(24, 24)), 100.0)
        banners.observe(listOf(game(24, 27)), 115.0)
        val banner = banners.active(115.0).getValue("g1")
        assertEquals(Triple("FIELD GOAL", "Buf", 115.0), Triple(banner.kind, banner.abbr, banner.at))
    }
}
