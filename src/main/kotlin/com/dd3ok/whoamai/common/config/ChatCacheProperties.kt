package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "who-am-ai.ai.cache")
data class ChatCacheProperties(
    var enabled: Boolean = true,
    var ttlSeconds: Long = 604800,
    var maxInMemoryEntries: Int = 128,
    var initialDelayMinMs: Long = 450,
    var initialDelayMaxMs: Long = 700,
    var targetStreamDurationMs: Long = 1600,
    var chunkDelayMinMs: Long = 18,
    var chunkDelayMaxMs: Long = 32,
    var pauseDelayMs: Long = 60
)
