package com.ubiquiti.hoteagg.service

import com.ubiquiti.hoteagg.model.HistoryEntry
import com.ubiquiti.hoteagg.model.IngestionOutcome
import com.ubiquiti.hoteagg.model.IngestionStatus
import com.ubiquiti.hoteagg.model.ParseResult
import com.ubiquiti.hoteagg.model.TelemetryMessage
import com.ubiquiti.hoteagg.repository.MessageHistoryRepository
import com.ubiquiti.hoteagg.repository.TelemetryMetricsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Validates one raw message, stores it and records the outcome. Never throws for bad input or
 * database errors; the result is always an [IngestionOutcome]. There are no retries: each
 * message is written at most once.
 *
 * Idempotency: a measurement is identified by (deviceId, client timestamp), enforced by the
 * unique constraint on telemetry_metrics; a repeat is reported as DUPLICATE and writes no second
 * row. The UUID generated here identifies one receive attempt (one history row) only. It is
 * never a device-supplied id and is never used for de-duplication.
 *
 * Crash behaviour: the history row is written once, after the outcome is known. If the process
 * dies in between, the measurement (if it was inserted) is durable but that attempt has no history
 * row; a device resend is then answered DUPLICATE. If the database write fails, nothing is stored,
 * FAILED is recorded when the database allows it, and the device may resend the same measurement
 * safely because the write is idempotent.
 */
@Service
class TelemetryIngestionService(
    private val parser: TelemetryMessageParser,
    private val metrics: TelemetryMetricsRepository,
    private val history: MessageHistoryRepository,
    private val mapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(raw: String, serverReceivedAt: Instant): IngestionOutcome {
        val messageId = UUID.randomUUID()
        return when (val result = parser.parse(raw)) {
            is ParseResult.Invalid -> reject(messageId, raw, serverReceivedAt, result)
            is ParseResult.Valid -> store(messageId, raw, serverReceivedAt, result.message)
        }
    }

    private fun reject(messageId: UUID, raw: String, receivedAt: Instant, invalid: ParseResult.Invalid): IngestionOutcome {
        val envelope = inspect(raw)
        log.warn("Invalid message {} from device {}: {}", messageId, envelope.deviceId, invalid)
        recordBestEffort(
            HistoryEntry(
                messageId, envelope.deviceId, envelope.clientTimestamp, receivedAt,
                IngestionStatus.INVALID, invalid.toString(), envelope.payload,
            )
        )
        return IngestionOutcome(messageId, IngestionStatus.INVALID, invalid.errors)
    }

    private fun store(messageId: UUID, raw: String, receivedAt: Instant, message: TelemetryMessage): IngestionOutcome {
        fun entry(status: IngestionStatus, error: String? = null) =
            HistoryEntry(messageId, message.deviceId, message.timestamp, receivedAt, status, error, raw)

        val status = try {
            // One atomic, idempotent statement (INSERT ... ON CONFLICT DO NOTHING); no transaction needed.
            if (metrics.insertIfAbsent(message, receivedAt)) IngestionStatus.PERSISTED else IngestionStatus.DUPLICATE
        } catch (e: Exception) {
            log.error("Failed to persist message {} from device {}", messageId, message.deviceId, e)
            recordBestEffort(entry(IngestionStatus.FAILED, "persistence error: ${e.javaClass.simpleName}"))
            return IngestionOutcome(messageId, IngestionStatus.FAILED, listOf("internal error, message not stored"))
        }
        if (status == IngestionStatus.DUPLICATE) {
            log.debug("Duplicate measurement for {} at {}", message.deviceId, message.timestamp)
        }
        // The measurement is already durable; a history failure is logged but does not change the outcome.
        recordBestEffort(entry(status))
        return IngestionOutcome(messageId, status)
    }

    /** History is an audit trail; a failure to write it must not break message handling. */
    private fun recordBestEffort(entry: HistoryEntry) {
        try {
            history.record(entry)
        } catch (e: Exception) {
            log.error("Failed to record history for message {}", entry.messageId, e)
        }
    }

    private data class Envelope(val deviceId: String, val clientTimestamp: Instant?, val payload: String?)

    /** Extracts whatever can be salvaged from a message that failed validation. */
    private fun inspect(raw: String): Envelope {
        val node = try {
            mapper.readTree(raw)
        } catch (e: JacksonException) {
            null
        }
        if (node == null || node.isMissingNode) return Envelope(UNKNOWN_DEVICE, null, null)
        val deviceId = node.get("deviceId")
            ?.takeIf { it.isString }?.asString()?.trim()
            ?.takeIf { it.isNotEmpty() }?.take(TelemetryMessageParser.MAX_DEVICE_ID_LENGTH)
            ?: UNKNOWN_DEVICE
        val timestamp = node.get("timestamp")?.takeIf { it.isString }?.let {
            runCatching { OffsetDateTime.parse(it.asString()).toInstant() }.getOrNull()
        }
        return Envelope(deviceId, timestamp, raw)
    }

    companion object {
        const val UNKNOWN_DEVICE = "unknown"
    }
}

