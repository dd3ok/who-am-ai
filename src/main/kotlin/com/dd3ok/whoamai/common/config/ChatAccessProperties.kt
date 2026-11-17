package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "chat-access")
data class ChatAccessProperties(
    var sessionLifetimeSeconds: Long = 86400,
    var sessionsPerIpPerMinute: Int = 5,
    var sessionsPerMinute: Int = 60,
    var messagesPerSessionPerMinute: Int = 10,
    var messagesPerIpPerMinute: Int = 30,
    var messagesPerIpPerDay: Int = 150,
    var messagesPerMinute: Int = 120,
    var blockViolationThreshold: Int = 5,
    var blockViolationWindowSeconds: Long = 300,
    var blockDurationSeconds: Long = 900,
    var trustedProxyHops: Int = 1,
    var trustForwardedHeadersFromAllRemotes: Boolean = false
)
