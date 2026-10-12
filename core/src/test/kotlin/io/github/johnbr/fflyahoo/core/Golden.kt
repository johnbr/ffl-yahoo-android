package io.github.johnbr.fflyahoo.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.max

/** Test resources and the comparison that holds the Kotlin output to the Python integration's. */
object Golden {
    fun text(path: String): String =
        requireNotNull(Golden::class.java.getResourceAsStream("/$path")) { "missing resource $path" }
            .bufferedReader().readText()

    fun fixture(name: String): String = text("fixtures/$name")

    fun json(path: String): JsonElement = Json.parseToJsonElement(text(path))

    /** snake_case and no nulls — the shape `tools/parity_dump.py` writes the Python dicts in. */
    @OptIn(ExperimentalSerializationApi::class)
    val SNAKE = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        explicitNulls = false
        encodeDefaults = true
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = SNAKE.encodeToJsonElement(serializer, value)

    /** Every difference between [expected] (Python) and [actual] (Kotlin), as `path: expected != actual`. */
    fun diff(expected: JsonElement?, actual: JsonElement?, path: String = "$", out: MutableList<String> = ArrayList()): List<String> {
        val e = expected.orNull()
        val a = actual.orNull()
        when {
            e == null && a == null -> {}
            e == null || a == null -> out += "$path: ${e ?: "∅"} != ${a ?: "∅"}"
            e is JsonObject && a is JsonObject -> {
                for (key in (e.keys + a.keys).sorted()) diff(e[key], a[key], "$path.$key", out)
            }
            e is JsonArray && a is JsonArray -> {
                if (e.size != a.size) out += "$path: ${e.size} items != ${a.size} items"
                for (i in 0 until minOf(e.size, a.size)) diff(e[i], a[i], "$path[$i]", out)
            }
            e is JsonPrimitive && a is JsonPrimitive -> if (!same(e, a)) out += "$path: $e != $a"
            else -> out += "$path: $e != $a"
        }
        return out
    }

    private fun JsonElement?.orNull(): JsonElement? = if (this == null || this == JsonNull) null else this

    private fun same(e: JsonPrimitive, a: JsonPrimitive): Boolean {
        if (e.isString || a.isString) return e.isString && a.isString && e.content == a.content
        val eb = e.booleanOrNull
        val ab = a.booleanOrNull
        if (eb != null || ab != null) return eb == ab
        val en = e.doubleOrNull
        val an = a.doubleOrNull
        if (en == null || an == null) return e.content == a.content
        return en == an || abs(en - an) <= 1e-9 * max(1.0, abs(en))
    }

    fun assertSame(expected: JsonElement?, actual: JsonElement?, what: String) {
        val differences = diff(expected, actual)
        if (differences.isNotEmpty()) {
            throw AssertionError("$what differs from the Python integration (${differences.size}):\n" + differences.take(25).joinToString("\n"))
        }
    }
}
