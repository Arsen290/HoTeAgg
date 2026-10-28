package com.ubiquiti.hoteagg.repository

import com.ubiquiti.hoteagg.model.HistoryEntry
import com.ubiquiti.hoteagg.model.TelemetryMessage
import java.time.Instant

interface TelemetryMetricsRepository {
    /** Inserts the measurement. Returns false (and writes nothing) if (deviceId, timestamp) already exists. */
    fun insertIfAbsent(message: TelemetryMessage, serverReceivedAt: Instant): Boolean
}

interface MessageHistoryRepository {
    fun record(entry: HistoryEntry)
}

