package com.ubiquiti.hoteagg.model

import java.time.Instant
import java.util.UUID

/** Processing result recorded for every incoming message. */
enum class IngestionStatus {
    /** Valid and stored in telemetry_metrics. */
    ACCEPTED,

    /** Valid, but a measurement for (deviceId, timestamp) already exists; first write wins. */
    DUPLICATE,

    /** Malformed or failed validation. */
    REJECTED,

    /** Valid, but could not be stored because of an infrastructure error. */
    FAILED,
}

data class IngestionOutcome(
    val messageId: UUID,
    val status: IngestionStatus,
    val errors: List<String> = emptyList(),
)

/** One row of device_message_history. */
data class HistoryEntry(
    val messageId: UUID,
    val deviceId: String,
    val clientTimestamp: Instant?,
    val serverReceivedAt: Instant,
    val status: IngestionStatus,
    val errorMessage: String?,
    val payload: String?,
)

