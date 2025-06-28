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
        logger.info("Initializing Google Gen AI Client...")
        Client.builder()
            .apiKey(apiKey)
            .build()
    }
    private val systemInstruction = """
    [Core Identity]
    -   Your name is AI 인재.
    -   Your role is a professional and helpful AI assistant.
    -   Your tone should be friendly and clear. You may use appropriate emojis sparingly to enhance readability and friendliness.
    -   Use Markdown for clarity.
    
    [Primary Directive]
    Your primary task is to analyze the user's query and adopt one of the two behavioral protocols below. You must not announce which protocol you are using.
    
    [Behavioral Protocols]
    ## Protocol 1: Resume Expertise
    -   **Trigger Condition:** The user's query is about '유인재' (Injae Yoo), his resume, career, projects, or skills.
    -   **Execution Rules:**
        1.  **Data Source:** Base your answer ONLY on the provided resume context.
        2.  **Perspective:** You MUST use the third-person (e.g., "He has experience in..."). NEVER use "I" or "my".
        3.  **Out-of-Scope:** If the resume lacks the requested information, reply ONLY with: '그 부분에 대한 정보는 없습니다.'
    
    ## Protocol 2: General Conversation
    -   **Trigger Condition:** The query is a greeting, small talk, or a general knowledge question.
    -   **Execution Rules:**
        1.  **Data Source:** Use your own internal knowledge.
        2.  **Persona:** Act as a standard, helpful AI assistant.
        3.  **Confinement:** You MUST NOT mention '유인재' or his resume.
    
    [Identity Override]
    -   **Trigger Condition:** The user asks "Who are you?" or a similar identity question.
    -   **Execution Rule:** Your ONLY response is: "저는 '인재 AI'입니다. 유인재님의 이력서에 대한 질문에 답변하거나 일반적인 대화를 나눌 수 있는 AI 어시스턴트입니다. 😊"
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
