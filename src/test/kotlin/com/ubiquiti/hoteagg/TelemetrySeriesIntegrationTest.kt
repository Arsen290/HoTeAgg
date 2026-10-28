package com.ubiquiti.hoteagg

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real HTTP + real PostgreSQL (Testcontainers; requires Docker): SQL, epoch bucketing and JSONB handling. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetrySeriesIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: JsonMapper

    private val http = HttpClient.newHttpClient()
    private val now: Instant = Instant.now()

    private fun device() = "series-" + UUID.randomUUID()

    private fun floor(t: Instant, seconds: Long) = Instant.ofEpochSecond(t.epochSecond / seconds * seconds)

    private fun insert(device: String, ts: Instant, stats: String) {
        jdbc.update(
            "INSERT INTO telemetry_metrics (device_id, client_timestamp, server_received_at, stats) " +
                "VALUES (?, ?, now(), CAST(? AS jsonb))",
            device, OffsetDateTime.ofInstant(ts, ZoneOffset.UTC), stats,
        )
    }

    private fun cpu(v: Number) = """{"cpuUtilPct": $v}"""

    private fun get(path: String): Pair<Int, JsonNode> {
        val response = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to mapper.readTree(response.body())
    }

    private fun points(body: JsonNode) = (0 until body["points"].size()).map { body["points"][it] }

    @Test
    fun `1h returns raw points in ascending order for the requested device and window only`() {
        val d = device()
        insert(d, now.minus(Duration.ofMinutes(10)), cpu(10))   // inserted out of order on purpose
        insert(d, now.minus(Duration.ofMinutes(30)), cpu(30))
        insert(d, now.minus(Duration.ofHours(2)), cpu(99))      // outside the window
        insert(device(), now.minus(Duration.ofMinutes(20)), cpu(77)) // other device

        val (status, body) = get("/api/devices/$d/metrics/cpuUtilPct/series?range=1h")

        assertEquals(200, status)
        val p = points(body)
        assertEquals(listOf(30.0, 10.0), p.map { it["value"].doubleValue() })
        assertTrue(Instant.parse(p[0]["timestamp"].asString()).isBefore(Instant.parse(p[1]["timestamp"].asString())))
    }

    @Test
    fun `24h averages per 5 minute bucket and labels buckets by their start`() {
        val d = device()
        val base = floor(now.minus(Duration.ofHours(2)), 1800)
        insert(d, base.plusSeconds(10), cpu(10))
        insert(d, base.plusSeconds(120), cpu(20))   // same bucket as above -> avg 15
        insert(d, base.plusSeconds(360), cpu(40))   // next bucket
        insert(d, now.minus(Duration.ofDays(3)), cpu(99)) // outside 24h

        val (status, body) = get("/api/devices/$d/metrics/cpuUtilPct/series?range=24h")

        assertEquals(200, status)
        val p = points(body)
        assertEquals(2, p.size)
        assertEquals(base, Instant.parse(p[0]["timestamp"].asString()))
        assertEquals(15.0, p[0]["value"].doubleValue(), 1e-9)
        assertEquals(base.plusSeconds(300), Instant.parse(p[1]["timestamp"].asString()))
        assertEquals(40.0, p[1]["value"].doubleValue(), 1e-9)
    }

    @Test
    fun `7d averages per 30 minute bucket and does not fabricate empty buckets`() {
        val d = device()
        val base = floor(now.minus(Duration.ofHours(2)), 1800)
        insert(d, base.plusSeconds(10), cpu(10))
        insert(d, base.plusSeconds(120), cpu(20))
        insert(d, base.plusSeconds(360), cpu(40))   // all in one 30 min bucket -> avg 23.33
        val old = floor(now.minus(Duration.ofDays(3)), 1800)
        insert(d, old.plusSeconds(7), cpu(50))

        val (status, body) = get("/api/devices/$d/metrics/cpuUtilPct/series?range=7d")

        assertEquals(200, status)
        val p = points(body)
        assertEquals(2, p.size)   // only non-empty buckets
        assertEquals(old, Instant.parse(p[0]["timestamp"].asString()))
        assertEquals(50.0, p[0]["value"].doubleValue(), 1e-9)
        assertEquals(base, Instant.parse(p[1]["timestamp"].asString()))
        assertEquals((10 + 20 + 40) / 3.0, p[1]["value"].doubleValue(), 1e-9)
    }

    @Test
    fun `missing, null, non numeric and malformed values are skipped without failing the request`() {
        val d = device()
        val t = now.minus(Duration.ofMinutes(30))
        insert(d, t.plusSeconds(1), """{"cpuUtilPct": "abc"}""")
        insert(d, t.plusSeconds(2), """{"cpuUtilPct": null}""")
        insert(d, t.plusSeconds(3), """{"memUtilPct": 5}""")
        insert(d, t.plusSeconds(4), """{"cpuUtilPct": true}""")
        insert(d, t.plusSeconds(5), """{"cpuUtilPct": {"nested": 1}}""")
        insert(d, t.plusSeconds(6), """{"cpuUtilPct": [1, 2]}""")
        insert(d, t.plusSeconds(7), cpu(42.5))

        listOf("1h", "24h", "7d").forEach { range ->
            val (status, body) = get("/api/devices/$d/metrics/cpuUtilPct/series?range=$range")
            assertEquals(200, status, range)
            assertEquals(listOf(42.5), points(body).map { it["value"].doubleValue() }, range)
        }
    }

    @Test
    fun `a metric that was never reported yields an empty series`() {
        val d = device()
        insert(d, now.minus(Duration.ofMinutes(5)), cpu(1))

        val (status, body) = get("/api/devices/$d/metrics/powerW/series?range=1h")

        assertEquals(200, status)
        assertEquals(0, body["points"].size())
    }

    @Test
    fun `unknown device yields 200 with empty points`() {
        val (status, body) = get("/api/devices/${device()}/metrics/cpuUtilPct/series?range=7d")

        assertEquals(200, status)
        assertEquals(0, body["points"].size())
    }

    @Test
    fun `invalid metric and range return 400`() {
        assertEquals(400, get("/api/devices/x/metrics/nope/series?range=1h").first)
        assertEquals(400, get("/api/devices/x/metrics/cpuUtilPct/series?range=2h").first)
    }
}

