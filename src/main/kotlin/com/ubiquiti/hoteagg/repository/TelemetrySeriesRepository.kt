package com.ubiquiti.hoteagg.repository

import com.ubiquiti.hoteagg.model.SeriesPoint
import java.time.Duration
import java.time.Instant

interface TelemetrySeriesRepository {
    /**
     * Numeric values of [statsKey] for [deviceId] with from <= client_timestamp <= to, ascending by time.
     * With a null [bucket] every measurement is returned; otherwise the average per non-empty
     * time bucket, timestamped with the bucket start. Missing or non-numeric values are skipped.
     */
    fun findSeries(deviceId: String, statsKey: String, from: Instant, to: Instant, bucket: Duration?): List<SeriesPoint>
}

