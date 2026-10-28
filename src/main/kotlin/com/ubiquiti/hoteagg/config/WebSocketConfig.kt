package com.ubiquiti.hoteagg.config

import com.ubiquiti.hoteagg.handler.TelemetryWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import java.time.Clock

@Configuration
@EnableWebSocket
class WebSocketConfig(private val handler: TelemetryWebSocketHandler) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/telemetry").setAllowedOrigins("*")
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

