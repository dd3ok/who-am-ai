package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "prompts")
data class PromptProperties(
    var systemPath: String = "",
    var routingInstructionPath: String = "",
    var routingTemplatePath: String = "",
    var ragTemplatePath: String = "",
    var conversationalTemplatePath: String = ""
)

@Configuration
@ConfigurationProperties(prefix = "spring.ai.google.genai.chat.options")
data class GeminiChatModelProperties(
    var models: List<String> = emptyList(),
    var model: String = "",
    var temperature: Float = 0.7f,
    var maxOutputTokens: Int = 8192
)
