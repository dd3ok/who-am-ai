package com.dd3ok.whoamai.config

import com.dd3ok.whoamai.adapter.`in`.web.StreamChatWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping

@Configuration
class WebSocketConfig {

    @Bean
    fun webSocketHandlerMapping(streamChatWebSocketHandler: StreamChatWebSocketHandler): HandlerMapping {
        val map = mapOf("/ws/chat" to streamChatWebSocketHandler)
        return SimpleUrlHandlerMapping(map).apply { order = 1 }
    }
}