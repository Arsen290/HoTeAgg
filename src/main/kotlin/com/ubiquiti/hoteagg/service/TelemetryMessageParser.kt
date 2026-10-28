package com.ubiquiti.hoteagg.service

import com.ubiquiti.hoteagg.model.ParseResult
import com.ubiquiti.hoteagg.model.TelemetryMessage
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Parses and validates raw JSON stats messages. Never throws for bad input:
 * every problem is reported as [ParseResult.Invalid].
 */
@Component
class TelemetryMessageParser(private val mapper: JsonMapper) {

    fun parse(raw: String): ParseResult {
        val root = try {
            mapper.readTree(raw)
        } catch (e: JacksonException) {
            return invalid("malformed JSON: ${e.originalMessage}")
        }
        if (root == null || !root.isObject) return invalid("message must be a JSON object")

        val errors = mutableListOf<String>()

        val type = root.get("type")
        if (type == null || !type.isString || type.asString() != TelemetryMessage.TYPE) {
            errors += "type must be \"${TelemetryMessage.TYPE}\""
        }

        val deviceId = root.get("deviceId")
            ?.takeIf { it.isString }?.asString()?.trim()
        if (deviceId.isNullOrEmpty()) {
            errors += "deviceId must be a non-blank string"
        } else if (deviceId.length > MAX_DEVICE_ID_LENGTH) {
            errors += "deviceId must be at most $MAX_DEVICE_ID_LENGTH characters"
        }

        val timestamp = parseTimestamp(root.get("timestamp"), errors)
        val stats = parseStats(root.get("stats"), errors)

        if (errors.isNotEmpty() || deviceId == null || timestamp == null || stats == null) {
            return ParseResult.Invalid(errors.ifEmpty { listOf("invalid message") })
        }
        return ParseResult.Valid(TelemetryMessage(TelemetryMessage.TYPE, deviceId, timestamp, stats))
    }

    private fun parseTimestamp(node: JsonNode?, errors: MutableList<String>): java.time.Instant? {
        if (node == null || !node.isString) {
            errors += "timestamp must be an ISO-8601 string"
            return null
        }
        return try {
            OffsetDateTime.parse(node.asString()).toInstant()
        } catch (e: DateTimeParseException) {
            errors += "timestamp is not a valid ISO-8601 instant: '${node.asString()}'"
            null
        }
    }

    private fun parseStats(node: JsonNode?, errors: MutableList<String>): Map<String, Double>? {
        if (node == null || !node.isObject) {
            errors += "stats must be a JSON object"
            return null
        }
        if (node.size() == 0) {
            errors += "stats must not be empty"
            return null
        }
        val result = LinkedHashMap<String, Double>()
        var ok = true
        for ((name, value) in node.properties()) {
            if (name.isBlank() || name.length > MAX_STAT_NAME_LENGTH) {
                errors += "stats key '$name' must be 1..$MAX_STAT_NAME_LENGTH characters"
                ok = false
                continue
            }
            if (!value.isNumber) {
                errors += "stats.$name must be a number"
                ok = false
                continue
            }
            val d = value.doubleValue()
            if (!d.isFinite()) {
                errors += "stats.$name must be a finite number"
                ok = false
                continue
            }
            val range = KNOWN_RANGES[name]
            if (range != null && d !in range) {
                errors += "stats.$name must be within ${range.start}..${range.endInclusive} but was $d"
                ok = false
                continue
            }
            result[name] = d
        }
        return if (ok) result else null
    }

    private fun invalid(error: String) = ParseResult.Invalid(listOf(error))

    companion object {
        const val MAX_DEVICE_ID_LENGTH = 128
        const val MAX_STAT_NAME_LENGTH = 64

        /** Plausible ranges for well-known stats; unknown stats only need to be finite. */
        val KNOWN_RANGES: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
            "cpuUtilPct" to 0.0..100.0,
            "memUtilPct" to 0.0..100.0,
            "rxBps" to 0.0..Double.MAX_VALUE,
            "txBps" to 0.0..Double.MAX_VALUE,
            "powerW" to 0.0..Double.MAX_VALUE,
            "temperatureC" to -273.15..1000.0,
        )
    }
}

