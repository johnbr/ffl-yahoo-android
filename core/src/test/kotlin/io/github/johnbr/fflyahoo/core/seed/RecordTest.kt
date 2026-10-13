package io.github.johnbr.fflyahoo.core.seed

import io.github.johnbr.fflyahoo.core.Golden
import io.github.johnbr.fflyahoo.core.state.LeagueState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Each team's season record, printed after its name: "Tesla (0-4)". */
class RecordTest {
    /** The w1 fixtures with team 1's record set to [fields], e.g. `"wins": 3, "losses": 1, "ties": 1`. */
    private fun recordOf(fields: String): String? {
        val seed = Golden.fixture("yahoo_redzone_2026_w1.json")
            .replaceFirst(Regex(""""wins":\s*0,\s*"losses":\s*0,\s*"ties":\s*0"""), fields)
        val data = Seed.leagueFromPayloads(
            Seed.parse(seed),
            Golden.fixture("yahoo_relay_stats_2026_w1.txt"),
            Golden.fixture("yahoo_relay_games_2026_w1.txt"),
            "999999",
            now = 1000.0,
        )
        return data.standings.flatMap { it.toList() }.first { it.teamId == "1" }.record
    }

    @Test
    fun `week one reads 0-0 for everyone`() {
        val seed = Seed.parse(Golden.fixture("yahoo_redzone_2026_w1.json"))
        val data = Seed.leagueFromPayloads(
            seed,
            Golden.fixture("yahoo_relay_stats_2026_w1.txt"),
            Golden.fixture("yahoo_relay_games_2026_w1.txt"),
            "999999",
            now = 1000.0,
        )
        assertEquals(setOf("0-0"), data.standings.flatMap { it.toList() }.map { it.record }.toSet())
        assertEquals("0-0", LeagueState.matchupRows(data).first().home.record)
    }

    @Test
    fun `a tie shows only once there is one`() {
        assertEquals("0-4", recordOf(""""wins": 0, "losses": 4, "ties": 0"""))
        assertEquals("3-1-1", recordOf(""""wins": 3, "losses": 1, "ties": 1"""))
    }

    @Test
    fun `a seed without a record gives none`() {
        assertEquals(null, recordOf(""""wins": null, "losses": null, "ties": null"""))
    }
}
