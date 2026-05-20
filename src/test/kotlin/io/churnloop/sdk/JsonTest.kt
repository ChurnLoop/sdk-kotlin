package io.churnloop.sdk

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class JsonTest {
    @Test
    fun `encodes primitive types`() {
        assertEquals("\"hello\"", JsonValue.Str("hello").encodeToJsonString())
        assertEquals("42", JsonValue.Num(42).encodeToJsonString())
        assertEquals("3.14", JsonValue.Num(3.14).encodeToJsonString())
        assertEquals("true", JsonValue.Bool(true).encodeToJsonString())
        assertEquals("false", JsonValue.Bool(false).encodeToJsonString())
        assertEquals("null", JsonValue.Null.encodeToJsonString())
    }

    @Test
    fun `encodes arrays`() {
        val arr = JsonValue.Arr(listOf(
            JsonValue.Str("a"),
            JsonValue.Num(1),
            JsonValue.Bool(true),
        ))
        assertEquals("[\"a\",1,true]", arr.encodeToJsonString())
    }

    @Test
    fun `encodes objects with deterministic key order`() {
        // Order is preserved via LinkedHashMap — important so the
        // wire payload is stable (helps debugging + signature-style
        // tests).
        val obj = JsonValue.Obj(linkedMapOf(
            "name" to JsonValue.Str("widget"),
            "count" to JsonValue.Num(3),
        ))
        assertEquals("{\"name\":\"widget\",\"count\":3}", obj.encodeToJsonString())
    }

    @Test
    fun `escapes quotes and backslashes`() {
        val s = JsonValue.Str("she said \"hi\\bye\"")
        assertEquals("\"she said \\\"hi\\\\bye\\\"\"", s.encodeToJsonString())
    }

    @Test
    fun `escapes newline tab and carriage return`() {
        val s = JsonValue.Str("a\nb\tc\rd")
        assertEquals("\"a\\nb\\tc\\rd\"", s.encodeToJsonString())
    }

    @Test
    fun `escapes control characters as uXXXX`() {
        // Form feed (0x0C) and other non-explicit control chars
        // must be emitted as \uXXXX per JSON spec.
        val s = JsonValue.Str("\u000C")
        assertEquals("\"\\u000c\"", s.encodeToJsonString())
    }

    @Test
    fun `encodeAny wraps primitives correctly`() {
        assertEquals(JsonValue.Str("x"), encodeAny("x"))
        assertEquals(JsonValue.Num(7), encodeAny(7))
        assertEquals(JsonValue.Bool(true), encodeAny(true))
        assertEquals(JsonValue.Null, encodeAny(null))
    }

    @Test
    fun `encodeAny wraps nested maps and lists`() {
        val nested = encodeAny(mapOf(
            "plan" to "growth",
            "items" to listOf(1, 2, 3),
            "meta" to mapOf("active" to true),
        ))
        val json = nested.encodeToJsonString()
        // Verify the shape — exact ordering depends on map iteration
        // (LinkedHashMap preserves insertion).
        assertEquals(
            "{\"plan\":\"growth\",\"items\":[1,2,3],\"meta\":{\"active\":true}}",
            json,
        )
    }

    @Test
    fun `encodeAny drops non-string map keys`() {
        // Defensive: server expects all property keys to be strings.
        // We silently drop entries whose key is not a String rather
        // than throw at SDK call-site.
        @Suppress("UNCHECKED_CAST")
        val mixed = mapOf<Any, Any>("ok" to "yes", 42 to "no") as Map<String, Any?>
        val out = encodeAny(mixed)
        val json = out.encodeToJsonString()
        // The Int key entry is dropped.
        assertEquals("{\"ok\":\"yes\"}", json)
    }
}
