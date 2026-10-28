package com.ubiquiti.hoteagg.service

import com.ubiquiti.hoteagg.model.HistoryEntry
import com.ubiquiti.hoteagg.model.IngestionStatus
import com.ubiquiti.hoteagg.model.TelemetryMessage
import com.ubiquiti.hoteagg.repository.MessageHistoryRepository
import com.ubiquiti.hoteagg.repository.TelemetryMetricsRepository
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryIngestionServiceTest {

    private class FakeMetrics : TelemetryMetricsRepository {
        val rows = LinkedHashMap<Pair<String, Instant>, Pair<TelemetryMessage, Instant>>()
        var failWith: RuntimeException? = null

        override fun insertIfAbsent(message: TelemetryMessage, serverReceivedAt: Instant): Boolean {
            failWith?.let { throw it }
            val key = message.deviceId to message.timestamp
            if (key in rows) return false
            rows[key] = message to serverReceivedAt
            return true
        }
    }

    private class FakeHistory : MessageHistoryRepository {
        val entries = mutableListOf<HistoryEntry>()
        var failWith: RuntimeException? = null

        override fun record(entry: HistoryEntry) {
            failWith?.let { throw it }
            entries += entry
        }
    }

    private val mapper = JsonMapper.builder().build()
    private val metrics = FakeMetrics()
    private val history = FakeHistory()
    private val service = TelemetryIngestionService(
        TelemetryMessageParser(mapper), metrics, history, TransactionOperations.withoutTransaction(), mapper,
    )
    private val receivedAt = Instant.parse("2026-10-26T14:03:21.500Z")

    private fun message(ts: String = "2026-10-26T14:03:20Z", stats: String = """{"cpuUtilPct": 37.5}""") =
        """{"type":"stats","deviceId":"host-1","timestamp":"$ts","stats":$stats}"""

    @Test
    fun `valid message is stored and recorded as accepted with separate timestamps`() {
        val outcome = service.ingest(message(), receivedAt)

        assertEquals(IngestionStatus.ACCEPTED, outcome.status)
        val (stored, serverTs) = metrics.rows.values.single()
        assertEquals(Instant.parse("2026-10-26T14:03:20Z"), stored.timestamp)
        assertEquals(receivedAt, serverTs)
        val h = history.entries.single()
        assertEquals(outcome.messageId, h.messageId)
        assertEquals(IngestionStatus.ACCEPTED, h.status)
        assertEquals(Instant.parse("2026-10-26T14:03:20Z"), h.clientTimestamp)
        assertEquals(receivedAt, h.serverReceivedAt)
    }

    @Test
    fun `each message gets a distinct server generated id`() {
        val a = service.ingest(message("2026-10-26T14:03:20Z"), receivedAt)
        val b = service.ingest(message("2026-10-26T14:03:30Z"), receivedAt)
        assertNotEquals(a.messageId, b.messageId)
    }

    @Test
    fun `duplicate device timestamp is not stored twice and first write wins`() {
        service.ingest(message(stats = """{"cpuUtilPct": 10}"""), receivedAt)
        val outcome = service.ingest(message(stats = """{"cpuUtilPct": 99}"""), receivedAt.plusSeconds(5))

        assertEquals(IngestionStatus.DUPLICATE, outcome.status)
        assertEquals(1, metrics.rows.size)
        assertEquals(10.0, metrics.rows.values.single().first.stats["cpuUtilPct"])
        assertEquals(listOf(IngestionStatus.ACCEPTED, IngestionStatus.DUPLICATE), history.entries.map { it.status })
    }

    @Test
    fun `invalid message is rejected, kept in history with payload and not stored`() {
        val raw = message(stats = """{"cpuUtilPct": 500}""")
        val outcome = service.ingest(raw, receivedAt)

        assertEquals(IngestionStatus.REJECTED, outcome.status)
        assertTrue(outcome.errors.single().contains("cpuUtilPct"))
        assertTrue(metrics.rows.isEmpty())
        val h = history.entries.single()
        assertEquals(IngestionStatus.REJECTED, h.status)
        assertEquals("host-1", h.deviceId)
        assertEquals(Instant.parse("2026-10-26T14:03:20Z"), h.clientTimestamp)
        assertEquals(raw, h.payload)
        assertTrue(h.errorMessage!!.contains("cpuUtilPct"))
    }

    @Test
    fun `malformed json is rejected with unknown device and no payload`() {
        val outcome = service.ingest("{not json", receivedAt)

        assertEquals(IngestionStatus.REJECTED, outcome.status)
        val h = history.entries.single()
        assertEquals(TelemetryIngestionService.UNKNOWN_DEVICE, h.deviceId)
        assertNull(h.payload)
        assertNull(h.clientTimestamp)
    }

    @Test
    fun `database failure yields FAILED outcome and does not throw`() {
        metrics.failWith = IllegalStateException("db down")

        val outcome = service.ingest(message(), receivedAt)

        assertEquals(IngestionStatus.FAILED, outcome.status)
        assertTrue(metrics.rows.isEmpty())
        assertEquals(IngestionStatus.FAILED, history.entries.single().status)
    }

    @Test
    fun `failure is not retried`() {
        var calls = 0
        val counting = object : TelemetryMetricsRepository {
            override fun insertIfAbsent(message: TelemetryMessage, serverReceivedAt: Instant): Boolean {
                calls++
                throw IllegalStateException("boom")
            }
        }
        TelemetryIngestionService(
            TelemetryMessageParser(mapper), counting, history, TransactionOperations.withoutTransaction(), mapper,
        ).ingest(message(), receivedAt)
        assertEquals(1, calls)
    }

    @Test
    fun `history write failure on rejected message does not throw`() {
        history.failWith = IllegalStateException("db down")
        assertEquals(IngestionStatus.REJECTED, service.ingest("garbage", receivedAt).status)
    }

    @Test
    fun `history write failure on valid message yields FAILED`() {
        history.failWith = IllegalStateException("db down")
        assertEquals(IngestionStatus.FAILED, service.ingest(message(), receivedAt).status)
    }
}

