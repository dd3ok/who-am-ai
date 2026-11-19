package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "who-am-ai.ai.decision")
data class GeminiDecisionProperties(
    var enabled: Boolean = true,
    var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    var apiKey: String = "",
    var model: String = "gemini-decision-1",
    var timeoutMs: Long = 5_000L,
    var topicConfidenceThreshold: Double = 0.55,
    var chunkNoulThreshold: Double = 0.50,
    var chunkFallbackNoulThreshold: Double = 0.35,
    var chunkHighConfidenceRescueThreshold: Double = 0.85,
    var maxSelectedChunks: Int = 8
)
