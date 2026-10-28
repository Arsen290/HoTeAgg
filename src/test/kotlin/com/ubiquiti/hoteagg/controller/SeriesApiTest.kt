package com.ubiquiti.hoteagg.controller

import com.ubiquiti.hoteagg.model.SeriesPoint
import com.ubiquiti.hoteagg.repository.TelemetrySeriesRepository
import com.ubiquiti.hoteagg.service.TelemetrySeriesService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** HTTP-level tests with a fake repository; SQL behaviour is covered by TelemetrySeriesIntegrationTest. */
class SeriesApiTest {

    private class Call(
        val deviceId: String,
        val statsKey: String,
        val from: Instant,
        val to: Instant,
        val bucket: Duration?,
    )

    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val calls = mutableListOf<Call>()
    private var result: List<SeriesPoint> = emptyList()

    private val repository = object : TelemetrySeriesRepository {
        override fun findSeries(deviceId: String, statsKey: String, from: Instant, to: Instant, bucket: Duration?): List<SeriesPoint> {
            calls += Call(deviceId, statsKey, from, to, bucket)
            return result
        }
    }

    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        SeriesController(TelemetrySeriesService(repository, Clock.fixed(now, ZoneOffset.UTC)))
    ).build()

    private fun url(metric: String = "cpuUtilPct", range: String? = "1h", device: String = "host-1") =
        "/api/devices/$device/metrics/$metric/series" + (range?.let { "?range=$it" } ?: "")

    @Test
    fun `valid request returns device, metric, range and points`() {
        result = listOf(
            SeriesPoint(Instant.parse("2026-10-28T11:10:00Z"), 10.5),
            SeriesPoint(Instant.parse("2026-10-28T11:20:00Z"), 20.0),
        )

        mvc.perform(get(url()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").value("host-1"))
            .andExpect(jsonPath("$.metric").value("cpuUtilPct"))
            .andExpect(jsonPath("$.range").value("1h"))
            .andExpect(jsonPath("$.points.length()").value(2))
            .andExpect(jsonPath("$.points[0].timestamp").value("2026-10-28T11:10:00Z"))
            .andExpect(jsonPath("$.points[0].value").value(10.5))
    }

    @Test
    fun `points keep the ascending order produced by the repository`() {
        result = listOf(
            SeriesPoint(Instant.parse("2026-10-28T11:00:00Z"), 1.0),
            SeriesPoint(Instant.parse("2026-10-28T11:05:00Z"), 2.0),
            SeriesPoint(Instant.parse("2026-10-28T11:10:00Z"), 3.0),
        )

        mvc.perform(get(url()))
            .andExpect(jsonPath("$.points[0].value").value(1.0))
            .andExpect(jsonPath("$.points[1].value").value(2.0))
            .andExpect(jsonPath("$.points[2].value").value(3.0))
    }

    @Test
    fun `1h range is raw and covers the last hour`() {
        mvc.perform(get(url(range = "1h"))).andExpect(status().isOk)

        val call = calls.single()
        assertEquals(null, call.bucket)
        assertEquals(now, call.to)
        assertEquals(now.minus(Duration.ofHours(1)), call.from)
        assertEquals("host-1", call.deviceId)
    }

    @Test
    fun `24h range uses 5 minute buckets`() {
        mvc.perform(get(url(range = "24h"))).andExpect(status().isOk).andExpect(jsonPath("$.range").value("24h"))

        val call = calls.single()
        assertEquals(Duration.ofMinutes(5), call.bucket)
        assertEquals(now.minus(Duration.ofHours(24)), call.from)
    }

    @Test
    fun `7d range uses 30 minute buckets`() {
        mvc.perform(get(url(range = "7d"))).andExpect(status().isOk).andExpect(jsonPath("$.range").value("7d"))

        val call = calls.single()
        assertEquals(Duration.ofMinutes(30), call.bucket)
        assertEquals(now.minus(Duration.ofDays(7)), call.from)
    }

    @Test
    fun `range defaults to 1h`() {
        mvc.perform(get(url(range = null))).andExpect(status().isOk).andExpect(jsonPath("$.range").value("1h"))
    }

    @Test
    fun `all supported metrics are accepted`() {
        listOf("cpuUtilPct", "memUtilPct", "rxBps", "txBps", "powerW", "temperatureC").forEach { metric ->
            mvc.perform(get(url(metric = metric))).andExpect(status().isOk).andExpect(jsonPath("$.metric").value(metric))
        }
        assertEquals(6, calls.size)
        assertEquals("temperatureC", calls.last().statsKey)
    }

    @Test
    fun `unsupported metric returns 400 and never reaches the repository`() {
        listOf("bogus", "CPUUTILPCT", "cpuUtilPct'; DROP TABLE telemetry_metrics;--", "stats").forEach { metric ->
            mvc.perform(get("/api/devices/host-1/metrics/{m}/series?range=1h", metric))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").exists())
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `unsupported range returns 400 and never reaches the repository`() {
        listOf("2h", "30d", "", "1H", "all").forEach { range ->
            mvc.perform(get(url(range = range))).andExpect(status().isBadRequest)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `no data returns 200 with an empty points array`() {
        result = emptyList()

        mvc.perform(get(url()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.points").isArray)
            .andExpect(jsonPath("$.points.length()").value(0))
    }
}

