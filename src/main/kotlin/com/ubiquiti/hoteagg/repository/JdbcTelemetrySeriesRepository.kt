package com.ubiquiti.hoteagg.repository

import com.ubiquiti.hoteagg.model.SeriesPoint
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JdbcTelemetrySeriesRepository(private val jdbc: JdbcClient) : TelemetrySeriesRepository {

    override fun findSeries(
        deviceId: String,
        statsKey: String,
        from: Instant,
        to: Instant,
        bucket: Duration?,
    ): List<SeriesPoint> {
        val statement = jdbc.sql(if (bucket == null) RAW_SQL else BUCKETED_SQL)
            .param("deviceId", deviceId)
            .param("key", statsKey)
            .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
            .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
        if (bucket != null) statement.param("bucket", "${bucket.seconds} seconds")
        return statement
            .query { rs, _ ->
                SeriesPoint(rs.getObject("ts", OffsetDateTime::class.java).toInstant(), rs.getDouble("value"))
            }
            .list()
    }

    private companion object {
        // Only rows whose value is a JSON number reach the cast, so malformed values cannot fail the query.
        // The metric key is always a bind parameter chosen from the ChartMetric allowlist.
        const val RAW_SQL = """
            SELECT client_timestamp AS ts, (stats ->> CAST(:key AS text))::double precision AS value
            FROM telemetry_metrics
            WHERE device_id = :deviceId
              AND client_timestamp >= :from AND client_timestamp <= :to
              AND jsonb_typeof(stats -> CAST(:key AS text)) = 'number'
            ORDER BY client_timestamp
            """

        const val BUCKETED_SQL = """
            SELECT time_bucket(CAST(:bucket AS interval), client_timestamp) AS ts,
                   avg((stats ->> CAST(:key AS text))::double precision) AS value
            FROM telemetry_metrics
            WHERE device_id = :deviceId
              AND client_timestamp >= :from AND client_timestamp <= :to
              AND jsonb_typeof(stats -> CAST(:key AS text)) = 'number'
            GROUP BY ts
            ORDER BY ts
            """
    }
}

