package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 작업 목적: 프롬프트 템플릿 및 시스템 지침을 외부 설정으로 주입한다.
 * 주요 로직: `prompts` 네임스페이스에서 문자열 값을 로드하여 도메인 서비스가 재사용 가능하도록 제공한다.
 */
@Configuration
@ConfigurationProperties(prefix = "prompts")
data class PromptProperties(
    var systemInstruction: String = "",
    var routingInstruction: String = "",
    var routingTemplate: String = "",
    var ragTemplate: String = "",
    var conversationalTemplate: String = ""
)

/**
 * 작업 목적: Google GenAI 챗 모델에 전달할 기본 옵션(모델명, 온도, 토큰 제한)을 설정에서 읽어온다.
 * 주요 로직: Spring AI `spring.ai.google.genai.chat.options` 경로를 바인딩해 커스텀 어댑터와 자동 구성이 동일한 값을 공유한다.
 */
@Configuration
@ConfigurationProperties(prefix = "spring.ai.google.genai.chat.options")
data class GeminiChatModelProperties(
    var model: String = "",
    var temperature: Float = 0.7f,
    var maxOutputTokens: Int = 8192
)

/**
 * 작업 목적: 이미지 생성용 모델명을 외부 설정에서 주입한다.
 * 주요 로직: `spring.ai.google.genai.image` 네임스페이스를 매핑해 이미지 전용 API 호출 시 사용할 모델·엔드포인트를 노출한다.
 */
@Configuration
@ConfigurationProperties(prefix = "spring.ai.google.genai.image")
data class GeminiImageModelProperties(
    var baseUrl: String = "https://generativelanguage.googleapis.com",
    var generatePath: String = "/v1/models/{model}:generateContent",
    var modelName: String = "",
    var temperature: Float = 0.3f,
    var seed: Int? = 1234,
    var mimeType: String = "image/jpeg"
)
