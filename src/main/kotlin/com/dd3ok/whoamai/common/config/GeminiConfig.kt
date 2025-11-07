package com.dd3ok.whoamai.common.config

import com.google.genai.Client
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 작업 목적: Spring AI 전환 이후에도 이미지 생성 등 SDK 전용 기능이 필요하므로 Client Bean만 유지한다.
 * 주요 로직: Spring AI `spring.ai.google.genai` 프로퍼티에서 API Key를 주입받아 `Client`를 만든다.
 */
@Deprecated("Spring AI auto-configuration 도입 후 제거 예정")
@Configuration
class GeminiConfig(
    @Value("\${spring.ai.google.genai.api-key}") private val apiKey: String
) {

    @Bean
    fun geminiClient(): Client {
        return Client.builder()
            .apiKey(apiKey)
            .build()
    }
}
