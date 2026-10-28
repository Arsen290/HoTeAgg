package com.ubiquiti.hoteagg.repository

import com.ubiquiti.hoteagg.model.HistoryEntry
import com.ubiquiti.hoteagg.model.TelemetryMessage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Types
import java.time.Instant

@Repository
class JdbcTelemetryMetricsRepository(
    private val jdbc: JdbcClient,
    private val mapper: JsonMapper,
) : TelemetryMetricsRepository {

    override fun insertIfAbsent(message: TelemetryMessage, serverReceivedAt: Instant): Boolean =
        jdbc.sql(
            """
            INSERT INTO telemetry_metrics (device_id, client_timestamp, server_received_at, stats)
            VALUES (:deviceId, :clientTimestamp, :receivedAt, CAST(:stats AS jsonb))
            ON CONFLICT (device_id, client_timestamp) DO NOTHING
            """.trimIndent()
        )
            .param("deviceId", message.deviceId)
            .param("clientTimestamp", message.timestamp.toUtc())
            .param("receivedAt", serverReceivedAt.toUtc())
            .param("stats", mapper.writeValueAsString(message.stats))
            .update() > 0
}

@Repository
class JdbcMessageHistoryRepository(private val jdbc: JdbcClient) : MessageHistoryRepository {

    override fun record(entry: HistoryEntry) {
        jdbc.sql(
            """
            INSERT INTO device_message_history
                (message_id, device_id, client_timestamp, server_received_at, status, error_message, payload)
            VALUES (:id, :deviceId, :clientTimestamp, :receivedAt, :status, :error, CAST(:payload AS jsonb))
            """.trimIndent()
        )
            .param("id", entry.messageId)
            .param("deviceId", entry.deviceId)
            .param("clientTimestamp", entry.clientTimestamp?.toUtc(), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("receivedAt", entry.serverReceivedAt.toUtc())
            .param("status", entry.status.name)
            .param("error", entry.errorMessage, Types.VARCHAR)
            .param("payload", entry.payload, Types.VARCHAR)
            .update()
    }
}

