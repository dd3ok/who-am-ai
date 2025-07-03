package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.config.GeminiModelProperties
import com.dd3ok.whoamai.config.PromptProperties
import com.dd3ok.whoamai.domain.ChatMessage
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class GeminiAdapter(
    @Value("\${gemini.api.key}") private val apiKey: String,
    private val modelProperties: GeminiModelProperties,
    private val promptProperties: PromptProperties,
) : GeminiPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val client: Client by lazy {
        Client.builder()
            .apiKey(apiKey)
            .build()
    }

    private val generationConfig: GenerateContentConfig by lazy {
        GenerateContentConfig.builder()
            .maxOutputTokens(modelProperties.maxOutputTokens)
            .temperature(modelProperties.temperature)
            .systemInstruction(Content.fromParts(Part.fromText(promptProperties.systemInstruction)))
            .build()
    }

    override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
        val apiHistory = history.map { msg ->
            Content.builder()
                .role(msg.role)
                .parts(Part.fromText(msg.text))
                .build()
        }

        return try {
            val responseStream = client.models
                .generateContentStream(modelProperties.name, apiHistory, generationConfig)

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

    override suspend fun summerizeContent(prompt: String): String {
        return try {
            val response = client.models.generateContent(modelProperties.name, prompt, generationConfig)
            response.text() ?: ""
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for summarization: ${e.message}", e)
            ""
        }
    }
}