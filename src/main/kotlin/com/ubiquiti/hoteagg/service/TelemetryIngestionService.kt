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
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Validates one raw message, stores it and records the outcome. Never throws for bad input or
 * database errors; the result is always an [IngestionOutcome]. There are no retries: each
 * message is written at most once.
 */
@Service
class TelemetryIngestionService(
    private val parser: TelemetryMessageParser,
    private val metrics: TelemetryMetricsRepository,
    private val history: MessageHistoryRepository,
    private val tx: TransactionOperations,
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
        log.warn("Rejected message {} from device {}: {}", messageId, envelope.deviceId, invalid)
        val entry = HistoryEntry(
            messageId, envelope.deviceId, envelope.clientTimestamp, receivedAt,
            IngestionStatus.REJECTED, invalid.toString(), envelope.payload,
        )
        recordBestEffort(entry)
        return IngestionOutcome(messageId, IngestionStatus.REJECTED, invalid.errors)
    }

    private fun store(messageId: UUID, raw: String, receivedAt: Instant, message: TelemetryMessage): IngestionOutcome {
        try {
            val inserted = tx.execute {
                val inserted = metrics.insertIfAbsent(message, receivedAt)
                val status = if (inserted) IngestionStatus.ACCEPTED else IngestionStatus.DUPLICATE
                history.record(
                    HistoryEntry(messageId, message.deviceId, message.timestamp, receivedAt, status, null, raw)
                )
                inserted
            } == true
            val status = if (inserted) IngestionStatus.ACCEPTED else IngestionStatus.DUPLICATE
            if (!inserted) log.debug("Duplicate measurement for {} at {}", message.deviceId, message.timestamp)
            return IngestionOutcome(messageId, status)
        } catch (e: Exception) {
            log.error("Failed to persist message {} from device {}", messageId, message.deviceId, e)
            recordBestEffort(
                HistoryEntry(
                    messageId, message.deviceId, message.timestamp, receivedAt,
                    IngestionStatus.FAILED, "persistence error: ${e.javaClass.simpleName}", raw,
                )
            )
            return IngestionOutcome(messageId, IngestionStatus.FAILED, listOf("internal error, message not stored"))
        }
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

