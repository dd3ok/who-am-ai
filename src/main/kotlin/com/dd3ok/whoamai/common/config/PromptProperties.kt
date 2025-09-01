package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "prompts")
data class PromptProperties(
    var systemInstruction: String = "",
    var routingInstruction: String = "",
    var routingTemplate: String = "",
    var ragTemplate: String = "",
    var conversationalTemplate: String = ""
)

@Configuration
@ConfigurationProperties(prefix = "gemini.chat.model")
data class GeminiChatModelProperties(
    var name: String = "",
    var text: String = "",
    var temperature: Float = 0.7f,
    var maxOutputTokens: Int = 8192
)

@Configuration
@ConfigurationProperties(prefix = "gemini.image.model")
data class GeminiImageModelProperties(
    var name: String = "",
)