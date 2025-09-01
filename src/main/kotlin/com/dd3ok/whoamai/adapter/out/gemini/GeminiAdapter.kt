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

    suspend fun generateImageContent(parts: List<Part>, systemInstruction: String): GenerateContentResponse {
        val config = GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
            .build()
        val contents = Content.builder().parts(parts).build()
        return try {
            client.models.generateContent(imageModelProperties.name, contents, config)
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for AIFitting: ${e.message}", e)
            throw e
        }
    }
}