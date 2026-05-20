package io.churnloop.sdk

/**
 * Minimal JSON encoder for the wire payload. The SDK avoids
 * pulling in kotlinx.serialization (would add ~200 KB to the
 * Android binary) — instead we hand-roll encoding for the small,
 * closed set of types we emit: strings, numbers, booleans, null,
 * arrays, and nested maps.
 *
 * Public so callers can build property values explicitly if they
 * need fine-grained control. Most callers use raw Kotlin types
 * (String, Int, Double, Boolean, Map, List) and rely on
 * [encodeAny] to wrap.
 */
public sealed class JsonValue {
    public data class Str(val value: String) : JsonValue()
    public data class Num(val value: Number) : JsonValue()
    public data class Bool(val value: Boolean) : JsonValue()
    public object Null : JsonValue()
    public data class Arr(val value: List<JsonValue>) : JsonValue()
    public data class Obj(val value: Map<String, JsonValue>) : JsonValue()
}

/**
 * Wrap an arbitrary Any value in [JsonValue]. Recognises String,
 * Number, Boolean, Map, List; anything else collapses to
 * [JsonValue.Null]. Mirrors `JSONValue.from` in the Swift SDK.
 */
@Suppress("UNCHECKED_CAST")
public fun encodeAny(value: Any?): JsonValue {
    return when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value)
        is Map<*, *> -> {
            val out = LinkedHashMap<String, JsonValue>(value.size)
            for ((k, v) in value) {
                if (k is String) out[k] = encodeAny(v)
            }
            JsonValue.Obj(out)
        }
        is List<*> -> JsonValue.Arr(value.map { encodeAny(it) })
        is Array<*> -> JsonValue.Arr(value.map { encodeAny(it) })
        else -> JsonValue.Null
    }
}

/**
 * Serialise a [JsonValue] tree to a compact JSON string. No
 * whitespace, no pretty-printing — keeps wire size down. Standard
 * RFC-8259 escaping for strings.
 */
public fun JsonValue.encodeToJsonString(): String {
    val sb = StringBuilder(64)
    writeJson(this, sb)
    return sb.toString()
}

private fun writeJson(value: JsonValue, sb: StringBuilder) {
    when (value) {
        is JsonValue.Str -> writeJsonString(value.value, sb)
        is JsonValue.Num -> sb.append(value.value.toString())
        is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
        JsonValue.Null -> sb.append("null")
        is JsonValue.Arr -> {
            sb.append('[')
            value.value.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                writeJson(v, sb)
            }
            sb.append(']')
        }
        is JsonValue.Obj -> {
            sb.append('{')
            var first = true
            for ((k, v) in value.value) {
                if (!first) sb.append(',')
                first = false
                writeJsonString(k, sb)
                sb.append(':')
                writeJson(v, sb)
            }
            sb.append('}')
        }
    }
}

private fun writeJsonString(s: String, sb: StringBuilder) {
    sb.append('"')
    for (ch in s) {
        // Common escapes explicitly. Form-feed (0x0C) and other
        // control chars under 0x20 fall through to the \uXXXX
        // branch — JSON requires all of those to be escaped.
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append('"')
}
