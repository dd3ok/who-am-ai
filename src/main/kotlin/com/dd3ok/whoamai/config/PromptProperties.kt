package com.dd3ok.whoamai.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "prompts")
data class PromptProperties(
    var systemInstruction: String = "",
    var ragTemplate: String = "",
    var conversationalTemplate: String = ""
)

@Configuration
@ConfigurationProperties(prefix = "gemini.model")
data class GeminiModelProperties(
    var name: String = "",
    var text: String = "",
    var temperature: Float = 0.7f,
    var maxOutputTokens: Int = 8192
)