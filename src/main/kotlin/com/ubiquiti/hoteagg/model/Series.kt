package com.ubiquiti.hoteagg.model

import java.time.Duration
import java.time.Instant

/**
 * Allowlist of chartable metrics. [apiName] is what clients send, [statsKey] is the JSONB key in
 * telemetry_metrics.stats. Only these fixed values ever reach SQL.
 */
enum class ChartMetric(val apiName: String, val statsKey: String) {
    CPU_UTIL_PCT("cpuUtilPct", "cpuUtilPct"),
    MEM_UTIL_PCT("memUtilPct", "memUtilPct"),
    RX_BPS("rxBps", "rxBps"),
    TX_BPS("txBps", "txBps"),
    POWER_W("powerW", "powerW"),
    TEMPERATURE_C("temperatureC", "temperatureC");

    companion object {
        fun fromApiName(name: String): ChartMetric? = entries.firstOrNull { it.apiName == name }
    }
}

/** Supported chart ranges. A null [bucket] means raw, non-aggregated measurements. */
enum class SeriesRange(val apiName: String, val window: Duration, val bucket: Duration?) {
    LAST_HOUR("1h", Duration.ofHours(1), null),
    LAST_DAY("24h", Duration.ofHours(24), Duration.ofMinutes(5)),
    LAST_WEEK("7d", Duration.ofDays(7), Duration.ofMinutes(30));

    companion object {
        fun fromApiName(name: String): SeriesRange? = entries.firstOrNull { it.apiName == name }
    }
}

data class SeriesPoint(val timestamp: Instant, val value: Double)

data class SeriesResponse(
    val deviceId: String,
    val metric: String,
    val range: String,
    val points: List<SeriesPoint>,
)

