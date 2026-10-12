package io.github.johnbr.fflyahoo.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Python's numeric behaviour, reproduced exactly.
 *
 * The engine is a port of the Home Assistant integration, and its outputs — point totals, event ids,
 * stat lines — are compared against that integration's to the cent. Kotlin's defaults differ from
 * Python's in ways that show: `String.format("%.2f")` rounds half-up on the shortest decimal and uses
 * the default locale (a German phone writes `12,74` into an event id), `+` over a list of doubles is
 * naive where CPython 3.12+ compensates, and there is no `%g` that drops a trailing `.0`.
 */
object Py {
    /** `round(x, digits)` — correctly rounded from the exact binary value, ties to even. */
    fun round(x: Double, digits: Int): Double {
        if (x.isNaN() || x.isInfinite()) return x
        val rounded = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
        // BigDecimal has no negative zero; Python keeps the sign (`round(-0.001, 2)` is `-0.0`).
        return if (rounded == 0.0 && (x < 0 || 1.0 / x < 0)) -0.0 else rounded
    }

    /** `sum(floats)` as CPython 3.12+ computes it: Neumaier-compensated. */
    fun sum(values: Iterable<Double>): Double {
        var result = 0.0
        var c = 0.0
        for (x in values) {
            val t = result + x
            c += if (Math.abs(result) >= Math.abs(x)) (result - t) + x else (x - t) + result
            result = t
        }
        if (c != 0.0 && c.isFinite()) result += c
        return result
    }

    inline fun <T> sumOf(items: Iterable<T>, selector: (T) -> Double): Double = sum(items.map(selector))

    /** `f"{x:.<digits>f}"` — ties to even on the exact value, always a `.` separator. */
    fun fixed(x: Double, digits: Int = 2): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        val text = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
        val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
        return if (negative && !text.startsWith("-")) "-$text" else text
    }

    /** `f"{x:+.<digits>f}"`. */
    fun signedFixed(x: Double, digits: Int = 2): String {
        val text = fixed(x, digits)
        return if (text.startsWith("-")) text else "+$text"
    }

    /** `f"{x:g}"` — six significant digits, trailing zeros dropped, `26` rather than `26.0`. */
    fun g(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        if (x == 0.0) return if (1.0 / x < 0) "-0" else "0"
        val rounded = BigDecimal(x).round(MathContext(6, RoundingMode.HALF_EVEN))
        val exponent = rounded.precision() - rounded.scale() - 1
        val stripped = rounded.stripTrailingZeros()
        if (exponent in -4..5) return stripped.toPlainString()
        val mantissa = stripped.movePointLeft(exponent).toPlainString()
        val sign = if (exponent < 0) "-" else "+"
        val digits = Math.abs(exponent).toString().padStart(2, '0')
        return "${mantissa}e$sign$digits"
    }

    /** `f"{x:+g}"`. */
    fun signedG(x: Double): String {
        val text = g(x)
        return if (text.startsWith("-")) text else "+$text"
    }

    /** `int(text)` or null — Python accepts surrounding whitespace and a sign. */
    fun intOrNull(text: String?): Int? = text?.trim()?.toIntOrNull()

    /** `float(text)` or null. */
    fun floatOrNull(text: String?): Double? {
        val t = text?.trim() ?: return null
        if (t.isEmpty()) return null
        return when (t.lowercase()) {
            "nan", "+nan", "-nan" -> Double.NaN
            "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
            else -> t.toDoubleOrNull()
        }
    }

    /** `str.isdigit()` for the ASCII digits the feeds use. */
    fun isDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }
}
