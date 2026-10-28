package com.ubiquiti.hoteagg

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end test: real WebSocket client, real PostgreSQL (Testcontainers; requires Docker). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetryIngestionIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun connect(replies: LinkedBlockingQueue<String>): WebSocketSession =
        StandardWebSocketClient().execute(object : TextWebSocketHandler() {
            override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                replies.add(message.payload)
            }
        }, null, URI.create("ws://localhost:$port/ws/telemetry")).get(10, TimeUnit.SECONDS)

    @Test
    fun `valid duplicate and invalid messages are stored and audited`() {
        val replies = LinkedBlockingQueue<String>()
        val session = connect(replies)
        val valid = """{"type":"stats","deviceId":"it-1","timestamp":"2026-10-26T14:03:20Z","stats":{"cpuUtilPct":37.5,"fanRpm":900}}"""

        session.sendMessage(TextMessage(valid))
        assertTrue(assertNotNull(replies.poll(10, TimeUnit.SECONDS)).contains("PERSISTED"))
        session.sendMessage(TextMessage(valid))
        assertTrue(assertNotNull(replies.poll(10, TimeUnit.SECONDS)).contains("DUPLICATE"))
        session.sendMessage(TextMessage("{broken"))
        assertTrue(assertNotNull(replies.poll(10, TimeUnit.SECONDS)).contains("INVALID"))
        session.close()

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM telemetry_metrics WHERE device_id = 'it-1'", Int::class.java))
        assertEquals(
            37.5,
            jdbc.queryForObject("SELECT (stats->>'cpuUtilPct')::float FROM telemetry_metrics WHERE device_id = 'it-1'", Double::class.java),
        )
        val statuses = jdbc.queryForList(
            "SELECT status FROM device_message_history WHERE device_id IN ('it-1','unknown') ORDER BY server_received_at",
            String::class.java,
        )
        assertEquals(listOf("PERSISTED", "DUPLICATE", "INVALID"), statuses)
    }
}

