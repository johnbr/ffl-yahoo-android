package io.github.johnbr.fflyahoo.core.scoring

import io.github.johnbr.fflyahoo.core.Py
import io.github.johnbr.fflyahoo.core.relay.Stats

/**
 * Fantasy points and stat-line text. Port of the scoring half of `yahoo_redzone.py`.
 *
 * Yahoo publishes no live fantasy totals on this tier: the points are computed by multiplying each
 * player's live stat line by the league's own modifiers, which is exact to the league's settings.
 */
object Scoring {
    /** Fantasy points for one stat line under one league's settings. */
    fun score(stats: Map<String, Double>, modifiers: Map<String, Double>): Double =
        Py.round(Py.sum(stats.map { (name, value) -> (modifiers[name] ?: 0.0) * value }), 2)

    /**
     * A kick lands in several stats at once: the total, a distance bucket, and past fifty yards both
     * the open "50+" bucket and the split ones. `(stat to drop, stats that already say it)`, applied
     * in order — the finer stat wins whenever it moved.
     */
    private val FG_OVERLAPS: List<Pair<String, List<String>>> = buildList {
        for (kind in listOf("Made", "Missed")) {
            add("fieldGoals${kind}50plus" to listOf("50through59", "60plus").map { "fieldGoals$kind$it" })
            add(
                "fieldGoals$kind" to listOf(
                    "0through19", "20through29", "30through39", "40through49", "50plus", "50through59", "60plus",
                ).map { "fieldGoals$kind$it" },
            )
        }
    }

    /** [values] without the field-goal stats a finer one already covers. */
    fun dropOverlaps(values: Map<String, Double>): Map<String, Double> {
        val out = LinkedHashMap(values)
        for ((coarse, finer) in FG_OVERLAPS) {
            if (finer.any { (out[it] ?: 0.0) != 0.0 }) out.remove(coarse)
        }
        return out
    }

    /** The count of carries says nothing a manager opened the lineup to learn; the roster line omits it. */
    private val STAT_LINE_OMIT = setOf("rushingAttempts")

    /** `3 Rec, 26 Rec Yds` — Yahoo's own phrasing for a live stat line. Zero-valued stats are dropped. */
    fun statLine(stats: Map<String, Double>): String {
        // A shutout is a defence's headline stat and its value is zero. `gamesPlayed` marks a defence.
        val keepZero = if ("gamesPlayed" in stats) setOf("pointsAllowed") else emptySet()
        val values = dropOverlaps(stats)
        val parts = ArrayList<String>()
        for ((name, label) in Stats.STAT_LINE) {
            if (name in STAT_LINE_OMIT) continue
            val value = values[name] ?: 0.0
            if (value == 0.0 && name !in keepZero) continue
            parts += "${Py.g(value)} $label"
        }
        return parts.joinToString(", ")
    }

    /** `1 Comp, 13 Pass Yds` — what changed between two stat lines. Negative movement keeps its sign. */
    fun describeDelta(before: Map<String, Double>, after: Map<String, Double>): String {
        val delta = LinkedHashMap<String, Double>()
        for (name in after.keys + before.keys) delta[name] = (after[name] ?: 0.0) - (before[name] ?: 0.0)
        val values = dropOverlaps(delta)
        val parts = ArrayList<String>()
        for ((name, label) in Stats.STAT_LINE) {
            val value = values[name] ?: 0.0
            if (value == 0.0) continue
            parts += "${Py.g(value)} $label"
        }
        return parts.joinToString(", ")
    }

    /** Label -> its position in [Stats.STAT_LINE]; a label printed twice ("Int") takes its last place. */
    private val LABEL_ORDER: Map<String, Int> = buildMap {
        Stats.STAT_LINE.forEachIndexed { index, (_, label) -> put(label, index) }
    }

    /**
     * `1 Rec` + `19 Rec Yds` -> `1 Rec, 19 Rec Yds`; `2 Rush Yds` + `1 Rush Yds` -> `3 Rush Yds`. Two
     * pieces of one play's stat line, summed by label and re-emitted in [describeDelta]'s order.
     */
    fun mergeStatDeltas(first: String, second: String): String {
        val totals = LinkedHashMap<String, Double>()
        for (text in listOf(first, second)) {
            for (part in text.split(",")) {
                val trimmed = part.trim()
                val value = trimmed.substringBefore(" ")
                val label = if (" " in trimmed) trimmed.substringAfter(" ") else ""
                if (label.isEmpty()) continue
                val n = Py.floatOrNull(value)
                totals[label] = (totals[label] ?: 0.0) + (n ?: 0.0)
            }
        }
        return totals.keys
            .sortedBy { LABEL_ORDER[it] ?: LABEL_ORDER.size }
            .mapNotNull { label ->
                val value = totals.getValue(label)
                if (value == 0.0) null else "${Py.g(value)} $label"
            }
            .joinToString(", ")
    }

    /** `1 Rec, 1 Rec Yds` -> `1 rec, 1 yd` — the compact, player-side rendering of a stat delta. */
    fun shortenStatDelta(text: String): String {
        val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (part in parts) {
            val value = part.substringBefore(" ")
            val label = if (" " in part) part.substringAfter(" ") else ""
            val entry = Stats.SHORT_STAT[label]
            if (entry == null) {
                out += part
                continue
            }
            val (category, standalone, bare) = entry
            var short = if (category in seen) bare else standalone
            seen += category
            if (short.endsWith("yds") && value.trimStart('+', '-') == "1") short = short.dropLast(1)
            out += "$value $short"
        }
        return out.joinToString(", ")
    }
}
