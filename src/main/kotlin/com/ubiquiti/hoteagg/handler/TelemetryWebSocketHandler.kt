package com.ubiquiti.hoteagg.handler

import com.ubiquiti.hoteagg.model.IngestionOutcome
import com.ubiquiti.hoteagg.service.TelemetryIngestionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * Thin WebSocket adapter: stamps the receive time, delegates to the ingestion service and
 * replies with a small acknowledgement. Contains no validation or persistence logic.
 */
@Component
class TelemetryWebSocketHandler(
    private val ingestion: TelemetryIngestionService,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        log.info("WebSocket opened: id={} remote={}", session.id, session.remoteAddress)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val receivedAt = clock.instant() // first thing, before validation and persistence
            reply(session, ingestion.ingest(message.payload, receivedAt))
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.warn("WebSocket transport error: id={} error={}", session.id, exception.toString())
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        log.info("WebSocket closed: id={} status={}", session.id, status)
    }

    private fun reply(session: WebSocketSession, outcome: IngestionOutcome) {
        val body = buildMap {
            put("status", outcome.status.name)
            put("messageId", outcome.messageId.toString())
            if (outcome.errors.isNotEmpty()) put("errors", outcome.errors)
        }
        if (session.isOpen) session.sendMessage(TextMessage(mapper.writeValueAsString(body)))
    }
}

