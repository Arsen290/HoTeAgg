package com.ubiquiti.hoteagg.service

import com.ubiquiti.hoteagg.model.ChartMetric
import com.ubiquiti.hoteagg.model.SeriesRange
import com.ubiquiti.hoteagg.model.SeriesResponse
import com.ubiquiti.hoteagg.repository.TelemetrySeriesRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

class InvalidSeriesRequestException(message: String) : RuntimeException(message)

@Service
class TelemetrySeriesService(
    private val series: TelemetrySeriesRepository,
    private val clock: Clock,
) {
    /**
     * The window is the last range.window up to the current server time. For aggregated ranges the
     * start is rounded down to a bucket boundary (UTC, epoch aligned, like the SQL buckets), so the
     * first bucket is complete instead of a misleading partial average.
     * Maximum points: 24h -> 289, 7d -> 337 (window / bucket, plus one for the unaligned end).
     */
    fun getSeries(deviceId: String, metricName: String, rangeName: String): SeriesResponse {
        val metric = ChartMetric.fromApiName(metricName)
            ?: throw InvalidSeriesRequestException(
                "Unsupported metric '$metricName'. Supported: ${ChartMetric.entries.joinToString { it.apiName }}"
            )
        val range = SeriesRange.fromApiName(rangeName)
            ?: throw InvalidSeriesRequestException(
                "Unsupported range '$rangeName'. Supported: ${SeriesRange.entries.joinToString { it.apiName }}"
            )

        val to = clock.instant()
        val windowStart = to.minus(range.window)
        val from = range.bucket?.let { windowStart.floorTo(it.seconds) } ?: windowStart
        val points = series.findSeries(deviceId, metric.statsKey, from, to, range.bucket)
        return SeriesResponse(deviceId, metric.apiName, range.apiName, points)
    }

    private fun Instant.floorTo(stepSeconds: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(epochSecond, stepSeconds) * stepSeconds)
}

