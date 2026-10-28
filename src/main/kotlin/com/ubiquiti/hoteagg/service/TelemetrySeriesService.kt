package com.ubiquiti.hoteagg.service

import com.ubiquiti.hoteagg.model.ChartMetric
import com.ubiquiti.hoteagg.model.SeriesRange
import com.ubiquiti.hoteagg.model.SeriesResponse
import com.ubiquiti.hoteagg.repository.TelemetrySeriesRepository
import org.springframework.stereotype.Service
import java.time.Clock

class InvalidSeriesRequestException(message: String) : RuntimeException(message)

@Service
class TelemetrySeriesService(
    private val series: TelemetrySeriesRepository,
    private val clock: Clock,
) {
    /** The window is the last range.window up to the current server time. */
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
        val points = series.findSeries(deviceId, metric.statsKey, to.minus(range.window), to, range.bucket)
        return SeriesResponse(deviceId, metric.apiName, range.apiName, points)
    }
}

