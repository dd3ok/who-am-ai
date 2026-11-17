package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "prompts")
data class PromptProperties(
    var systemPath: String = "",
    var routingInstructionPath: String = "",
    var routingTemplatePath: String = "",
    var ragTemplatePath: String = "",
    var conversationalTemplatePath: String = ""
)

@ConfigurationProperties(prefix = "who-am-ai.ai.chat")
data class GeminiChatModelProperties(
    var models: List<String?>? = emptyList(),
    var model: String? = "",
    var reasoningEffort: String = "medium",
    var selectorReasoningEffort: String = "low",
    var maxOutputTokens: Int = 8192,
    var rateLimitCooldownMs: Long = 60_000L
)
