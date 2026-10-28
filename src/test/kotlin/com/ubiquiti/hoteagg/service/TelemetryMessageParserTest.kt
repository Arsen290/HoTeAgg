package com.ubiquiti.hoteagg.service

import com.ubiquiti.hoteagg.model.ParseResult
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelemetryMessageParserTest {

    private val parser = TelemetryMessageParser(JsonMapper.builder().build())

    private fun json(
        type: String = "\"stats\"",
        deviceId: String = "\"host-0042\"",
        timestamp: String = "\"2026-10-26T14:03:20Z\"",
        stats: String = """{"cpuUtilPct": 37.5, "rxBps": 845200000, "temperatureC": 58.2}""",
    ) = """{"type": $type, "deviceId": $deviceId, "timestamp": $timestamp, "stats": $stats}"""

    private fun invalid(raw: String): String {
        val result = parser.parse(raw)
        assertIs<ParseResult.Invalid>(result, "expected Invalid for: $raw")
        assertTrue(result.errors.isNotEmpty())
        return result.errors.joinToString("; ")
    }

    // ---- valid ----

    @Test
    fun `valid message is parsed`() {
        val result = parser.parse(json())
        assertIs<ParseResult.Valid>(result)
        assertEquals("host-0042", result.message.deviceId)
        assertEquals(Instant.parse("2026-10-26T14:03:20Z"), result.message.timestamp)
        assertEquals(mapOf("cpuUtilPct" to 37.5, "rxBps" to 845200000.0, "temperatureC" to 58.2), result.message.stats)
    }

    @Test
    fun `timestamp with offset is normalised to the same instant`() {
        val result = parser.parse(json(timestamp = "\"2026-10-26T16:03:20+02:00\""))
        assertIs<ParseResult.Valid>(result)
        assertEquals(Instant.parse("2026-10-26T14:03:20Z"), result.message.timestamp)
    }

    @Test
    fun `unknown stat keys are accepted and boundary values are valid`() {
        val result = parser.parse(json(stats = """{"fanRpm": 1200, "cpuUtilPct": 0, "memUtilPct": 100, "powerW": 0}"""))
        assertIs<ParseResult.Valid>(result)
        assertEquals(1200.0, result.message.stats["fanRpm"])
    }

    @Test
    fun `negative temperature is allowed`() {
        assertIs<ParseResult.Valid>(parser.parse(json(stats = """{"temperatureC": -20.5}""")))
    }

    // ---- malformed ----

    @Test
    fun `malformed json is reported without throwing`() {
        assertTrue(invalid("{not json").contains("malformed JSON"))
        invalid("")
        invalid("   ")
    }

    @Test
    fun `non-object json is rejected`() {
        assertTrue(invalid("[1,2,3]").contains("JSON object"))
        invalid("42")
        invalid("null")
    }

    @Test
    fun `missing fields are reported together`() {
        val message = invalid("{}")
        listOf("type", "deviceId", "timestamp", "stats").forEach { assertTrue(message.contains(it), it) }
    }

    @Test
    fun `wrong field types are rejected`() {
        assertTrue(invalid(json(deviceId = "42")).contains("deviceId"))
        assertTrue(invalid(json(timestamp = "1761487400")).contains("timestamp"))
        assertTrue(invalid(json(stats = "[1]")).contains("stats"))
        assertTrue(invalid(json(type = "1")).contains("type"))
    }

    @Test
    fun `invalid timestamp string is rejected`() {
        assertTrue(invalid(json(timestamp = "\"yesterday\"")).contains("timestamp"))
        assertTrue(invalid(json(timestamp = "\"2026-10-26 14:03:20\"")).contains("timestamp"))
    }

    @Test
    fun `non-numeric stat values are rejected`() {
        assertTrue(invalid(json(stats = """{"cpuUtilPct": "37.5"}""")).contains("stats.cpuUtilPct must be a number"))
        invalid(json(stats = """{"cpuUtilPct": null}"""))
        invalid(json(stats = """{"cpuUtilPct": true}"""))
    }

    // ---- invalid ranges / values ----

    @Test
    fun `wrong type value is rejected`() {
        assertTrue(invalid(json(type = "\"event\"")).contains("type"))
    }

    @Test
    fun `blank or oversized deviceId is rejected`() {
        assertTrue(invalid(json(deviceId = "\"  \"")).contains("deviceId"))
        assertTrue(invalid(json(deviceId = "\"${"x".repeat(129)}\"")).contains("deviceId"))
    }

    @Test
    fun `empty stats is rejected`() {
        assertTrue(invalid(json(stats = "{}")).contains("must not be empty"))
    }

    @Test
    fun `non-finite values are rejected`() {
        // 1e999 overflows a double to Infinity
        assertTrue(invalid(json(stats = """{"fanRpm": 1e999}""")).contains("finite"))
        assertTrue(invalid(json(stats = """{"fanRpm": -1e999}""")).contains("finite"))
    }

    @Test
    fun `negative counters and power are rejected`() {
        assertTrue(invalid(json(stats = """{"rxBps": -1}""")).contains("rxBps"))
        assertTrue(invalid(json(stats = """{"txBps": -0.5}""")).contains("txBps"))
        assertTrue(invalid(json(stats = """{"powerW": -3}""")).contains("powerW"))
    }

    @Test
    fun `percentages outside 0 to 100 are rejected`() {
        assertTrue(invalid(json(stats = """{"cpuUtilPct": 100.1}""")).contains("cpuUtilPct"))
        assertTrue(invalid(json(stats = """{"memUtilPct": -0.1}""")).contains("memUtilPct"))
    }

    @Test
    fun `temperature below absolute zero is rejected`() {
        assertTrue(invalid(json(stats = """{"temperatureC": -300}""")).contains("temperatureC"))
    }

    @Test
    fun `one bad stat invalidates the whole message and all errors are listed`() {
        val message = invalid(json(stats = """{"cpuUtilPct": 150, "powerW": -1, "memUtilPct": 50}"""))
        assertTrue(message.contains("cpuUtilPct") && message.contains("powerW"))
    }
}

