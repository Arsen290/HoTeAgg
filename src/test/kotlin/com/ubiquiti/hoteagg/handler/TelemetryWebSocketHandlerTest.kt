package com.ubiquiti.hoteagg.handler

import com.ubiquiti.hoteagg.model.HistoryEntry
import com.ubiquiti.hoteagg.model.TelemetryMessage
import com.ubiquiti.hoteagg.repository.MessageHistoryRepository
import com.ubiquiti.hoteagg.repository.TelemetryMetricsRepository
import com.ubiquiti.hoteagg.service.TelemetryIngestionService
import com.ubiquiti.hoteagg.service.TelemetryMessageParser
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryWebSocketHandlerTest {

    private val mapper = JsonMapper.builder().build()
    private val receivedAt = Instant.parse("2026-10-26T14:03:21Z")
    private val stored = mutableListOf<Pair<TelemetryMessage, Instant>>()
    private var storeFailure: RuntimeException? = null

    private val service = TelemetryIngestionService(
        TelemetryMessageParser(mapper),
        object : TelemetryMetricsRepository {
            override fun insertIfAbsent(message: TelemetryMessage, serverReceivedAt: Instant): Boolean {
                storeFailure?.let { throw it }
                stored += message to serverReceivedAt
                return true
            }
        },
        object : MessageHistoryRepository {
            override fun record(entry: HistoryEntry) {}
        },
        TransactionOperations.withoutTransaction(),
        mapper,
    )
    private val handler = TelemetryWebSocketHandler(service, mapper, Clock.fixed(receivedAt, ZoneOffset.UTC))

    private val sent = mutableListOf<String>()
    private fun session(open: Boolean = true, sendFails: Boolean = false): WebSocketSession {
        val session = Mockito.mock(WebSocketSession::class.java)
        Mockito.`when`(session.isOpen).thenReturn(open)
        Mockito.`when`(session.id).thenReturn("s1")
        Mockito.doAnswer {
            if (sendFails) throw IOException("closed")
            sent += (it.getArgument<WebSocketMessage<*>>(0) as TextMessage).payload
            null
        }.`when`(session).sendMessage(Mockito.any())
        return session
    }

    private val valid =
        """{"type":"stats","deviceId":"host-1","timestamp":"2026-10-26T14:03:20Z","stats":{"cpuUtilPct":1}}"""

    @Test
    fun `valid message is stored with the receive time from the clock and acknowledged`() {
        handler.handleMessage(session(), TextMessage(valid))

        assertEquals(receivedAt, stored.single().second)
        assertTrue(sent.single().contains("\"status\":\"ACCEPTED\""))
    }

    @Test
    fun `invalid and malformed messages are answered with REJECTED`() {
        handler.handleMessage(session(), TextMessage("{oops"))
        handler.handleMessage(session(), TextMessage("""{"type":"x"}"""))

        assertEquals(2, sent.size)
        assertTrue(sent.all { it.contains("\"status\":\"REJECTED\"") && it.contains("errors") })
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `database failure is reported to the device and does not propagate`() {
        storeFailure = IllegalStateException("db down")
        handler.handleMessage(session(), TextMessage(valid))
        assertTrue(sent.single().contains("\"status\":\"FAILED\""))
    }

    @Test
    fun `disconnected client and lifecycle events do not throw`() {
        handler.handleMessage(session(sendFails = true), TextMessage(valid))
        handler.handleMessage(session(open = false), TextMessage(valid))
        handler.handleTransportError(session(), RuntimeException("reset"))
        handler.afterConnectionClosed(session(), CloseStatus.GOING_AWAY)
        assertEquals(2, stored.size)
    }
}

