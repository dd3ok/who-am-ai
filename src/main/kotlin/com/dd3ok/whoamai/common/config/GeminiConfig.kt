package com.dd3ok.whoamai.common.config

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GeminiConfig(
    @Value("\${gemini.api.key}") private val apiKey: String,
    private val modelProperties: GeminiChatModelProperties,
    private val promptProperties: PromptProperties
) {

    @Bean
    fun geminiClient(): Client {
        return Client.builder()
            .apiKey(apiKey)
            .build()
    }

    @Bean
    @Qualifier("generationConfig")
    fun generationConfig(): GenerateContentConfig {
        return GenerateContentConfig.builder()
            .maxOutputTokens(modelProperties.maxOutputTokens)
            .temperature(modelProperties.temperature)
            .systemInstruction(Content.fromParts(Part.fromText(promptProperties.systemInstruction)))
            .build()
    }

    @Bean
    @Qualifier("routingConfig")
    fun routingConfig(): GenerateContentConfig {
        return GenerateContentConfig.builder()
            // 라우팅은 간단한 JSON만 필요하므로 토큰 제한을 낮춰 비용 절감
            .maxOutputTokens(512)
            // 일관된 JSON 출력을 위해 온도를 낮춤
            .temperature(0.1f)
            .systemInstruction(Content.fromParts(Part.fromText(promptProperties.routingInstruction)))
            .build()
    }

    @Bean
    @Qualifier("summarizationConfig")
    fun summarizationConfig(): GenerateContentConfig {
        return GenerateContentConfig.builder()
            .maxOutputTokens(1024)
            .temperature(0.5f)
            // 요약 작업은 별도 시스템 지침이 필요 없을 수 있음 (필요 시 추가)
            .build()
    }
}