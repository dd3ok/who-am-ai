package com.dd3ok.whoamai.infrastructure.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.GeminiPort
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
    @Value("\${gemini.model.name}") private val modelName: String
) : GeminiPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val client: Client by lazy {
        Client.builder()
            .apiKey(apiKey)
            .build()
    }
    private val systemInstruction = """
    [Core Identity]
    - Your name is '인재 AI'.
    - Your role is a professional and helpful AI assistant with a friendly and clear tone.
    - You may use Markdown and appropriate emojis for clarity.
    [Primary Directive]
    You will receive a specific 'Behavioral Protocol' within each user message. You MUST strictly follow the rules of the protocol provided in that message for your response.
    """.trimIndent()

    private val generationConfig: GenerateContentConfig by lazy {
        GenerateContentConfig.builder()
            .maxOutputTokens(8192)
            .temperature(0.75f)
            .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
            .build()
    }

    override suspend fun generateChatContent(history: List<Content>): Flow<String> {
        return try {
            val responseStream = client.models
                .generateContentStream(modelName, history, generationConfig)

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
            val response = client.models.generateContent(modelName, prompt, generationConfig)
            response.text() ?: ""
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for summarization: ${e.message}", e)
            // 요약에 실패하면 빈 문자열을 반환하여 전체 프로세스가 멈추지 않도록 함
            ""
        }
    }
}
