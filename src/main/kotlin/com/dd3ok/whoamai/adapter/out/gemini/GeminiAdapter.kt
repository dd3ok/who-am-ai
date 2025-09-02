package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import com.dd3ok.whoamai.domain.ChatMessage
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class GeminiAdapter(
    private val client: Client,
    private val chatModelProperties: GeminiChatModelProperties,
    private val imageModelProperties: GeminiImageModelProperties,
    @Qualifier("generationConfig") private val generationConfig: GenerateContentConfig,
    @Qualifier("routingConfig") private val routingConfig: GenerateContentConfig,
    @Qualifier("summarizationConfig") private val summarizationConfig: GenerateContentConfig
) : GeminiPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
        val apiHistory = history.map { msg ->
            Content.builder()
                .role(msg.role)
                .parts(Part.fromText(msg.text))
                .build()
        }

        return try {
            val responseStream = client.models
                .generateContentStream(chatModelProperties.name, apiHistory, generationConfig)

            flow {
                for (response in responseStream) {
                    response.text()?.let { emit(it) }
                }
            }
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API: ${e.message}", e)
            flowOf("죄송합니다, AI 응답 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        }
    }

    override suspend fun generateContent(prompt: String, purpose: String): String {
        val config = when (purpose) {
            "routing" -> routingConfig
            "summarization" -> summarizationConfig
            else -> generationConfig
        }

        return try {
            val response = client.models.generateContent(chatModelProperties.name, prompt, config)
            response.text() ?: ""
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for purpose '$purpose': ${e.message}", e)
            ""
        }
    }

    suspend fun generateImageContent(clothingImagePart: Part, personImagePart: Part): GenerateContentResponse {
        val instructionParts = listOf(
            Part.fromText("시스템 지시: 멀티 이미지 입력을 받아 인물 이미지(베이스)에 의류 이미지(레퍼런스)를 자연스럽게 착용시키는 합성을 수행한다. 변경 대상 외 모든 요소는 보존한다(얼굴, 피부 톤, 머리카락, 손, 배경)."),
            Part.fromText("작업: 인물에게 레퍼런스 의류를 자연스럽게 착용시킨다."),
            Part.fromText("정합 조건: 목둘레·어깨선·소매/밑단 길이·실루엣을 인물 포즈에 맞춘다."),
            Part.fromText("물리 일관성: 주름/광택/그림자/오클루전(팔/손/머리카락 가림)을 현실적으로 반영한다."),
            Part.fromText("보존 조건: 얼굴·피부 톤·헤어·손·액세서리·배경은 변경 금지."),
            Part.fromText("금지: 신체 왜곡·로고/패턴의 부자연스러운 비틀림·경계 번짐. 절대 이미지를 그대로 잘라 붙여서는 안 된다."),
            Part.fromText("출력 형태: 텍스트 없이 이미지만 출력")
        )
        val systemInstruction = Content.builder().parts(instructionParts).build()
        val config = GenerateContentConfig.builder()
            .systemInstruction(systemInstruction)
            .seed(1234)
            .temperature(0.4f)
            .topP(0.9f)
            .topK(30.0f)
            .build()

        val userContent = Content.builder()
            .role("user")
            .parts(listOf(clothingImagePart, personImagePart))
            .build()

        val contents = listOf(userContent)

        return try {
            client.models.generateContent(imageModelProperties.name, contents, config)
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for AIFitting: ${e.message}", e)
            throw e
        }
    }
}