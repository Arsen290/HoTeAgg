package com.ubiquiti.hoteagg.model

import java.time.Instant

data class TelemetryMessage(
    val type: String,
    val deviceId: String,
    val timestamp: Instant,
    val stats: Map<String, Double>,
) {
    companion object {
        const val TYPE = "stats"
    }
}

sealed interface ParseResult {
    data class Valid(val message: TelemetryMessage) : ParseResult

    /** [errors] is never empty; each entry names the offending field. */
    data class Invalid(val errors: List<String>) : ParseResult {
        override fun toString() = errors.joinToString("; ")
    }
}

