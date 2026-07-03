package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "websocket")
data class WebSocketProperties(
    var allowedOrigins: List<String> = listOf("https://dd3ok.github.io", "http://localhost:3000"),
    var allowNullOrigin: Boolean = false,
    var responseTimeoutMs: Long = 30_000L
)
