package com.dd3ok.whoamai.application.service // 혹은 적절한 service 패키지

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.StreamMessage
import com.google.genai.types.Content
import com.google.genai.types.Part
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ChatService(
    private val geminiPort: GeminiPort,
    private val chatHistoryRepository: ChatHistoryRepository
) : ChatUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        // 전략 1: 슬라이딩 윈도우를 위한 설정 (API 호출 시)
        private const val API_WINDOW_TOKENS = 2048 // API에 보낼 최대 토큰 수

        // 전략 2: 요약을 위한 설정 (DB 저장 시)
        private const val SUMMARY_TRIGGER_TOKENS = 4096 // 이 토큰 수를 넘으면 요약 실행
        private const val SUMMARY_SOURCE_MESSAGES = 5 // 요약할 오래된 메시지 개수
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        val userId = message.uuid
        val prompt = message.content

        // 1. DB에서 전체 대화 기록을 불러옵니다.
        val domainHistory = chatHistoryRepository.findByUserId(userId)
            ?: ChatHistory(userId = userId)

        // 2. 현재 사용자 메시지를 전체 대화 기록에 추가합니다.
        domainHistory.addMessage(ChatMessage(role = "user", text = prompt))

        // 3. (전략 1) 슬라이딩 윈도우: API에 보낼 '최신' 대화 기록을 생성합니다.
        val apiHistory: List<Content> = createApiHistoryWindow(domainHistory)

        // 4. API를 호출합니다. (전체 기록이 아닌, 창문만큼의 기록만 전송)
        val responseFlow = geminiPort.generateChatContent(apiHistory)
        val modelResponseBuilder = StringBuilder()

        return responseFlow
            .onEach { chunk -> modelResponseBuilder.append(chunk) }
            .onCompletion { cause ->
                if (cause == null) {
                    val fullResponse = modelResponseBuilder.toString()
                    if (fullResponse.isNotBlank()) {
                        // 5. 모델의 응답을 '전체' 대화 기록에 추가합니다.
                        domainHistory.addMessage(ChatMessage(role = "model", text = fullResponse))

                        // 6. (전략 2) 요약: DB에 저장하기 전, 전체 기록이 너무 길면 요약합니다.
                        val finalHistoryToSave = summarizeHistoryIfNeeded(domainHistory)

                        // 7. 최종본(요약되었거나, 그대로이거나)을 DB에 저장합니다.
                        chatHistoryRepository.save(finalHistoryToSave)
                        logger.info("Successfully processed and saved chat history for user: {}", userId)
                    }
                } else {
                    logger.error("Chat stream failed for user: {}. History not saved.", userId, cause)
                }
            }
    }

    /**
     * (전략 1) 슬라이딩 윈도우: 전체 대화 기록에서 API에 보낼 최신 부분만 잘라냅니다.
     */
    private fun createApiHistoryWindow(domainHistory: ChatHistory): List<Content> {
        var currentTokens = 0
        val recentMessages = mutableListOf<ChatMessage>()

        // 최신 메시지부터 역순으로 순회
        for (msg in domainHistory.history.reversed()) {
            val estimatedTokens = estimateTokens(msg.text)
            if (currentTokens + estimatedTokens > API_WINDOW_TOKENS) {
                break // 최대 토큰 수를 초과하면 중단
            }
            recentMessages.add(msg)
            currentTokens += estimatedTokens
        }
        recentMessages.reverse() // 다시 시간 순서대로 뒤집기

        return recentMessages.map { msg ->
            Content.builder()
                .role(msg.role)
                .parts(listOf(Part.fromText(msg.text)))
                .build()
        }
    }

    /**
     * (전략 2) 요약: 전체 대화 기록이 임계값을 넘으면, 오래된 부분을 요약하여 대체합니다.
     */
    private suspend fun summarizeHistoryIfNeeded(originalHistory: ChatHistory): ChatHistory {
        val totalTokens = originalHistory.history.sumOf { estimateTokens(it.text) }

        // 요약이 필요 없는 경우, 원본을 그대로 반환
        if (totalTokens < SUMMARY_TRIGGER_TOKENS || originalHistory.history.size < SUMMARY_SOURCE_MESSAGES + 2) {
            return originalHistory
        }

        logger.info("History for user {} exceeds token threshold. Starting summarization.", originalHistory.userId)

        // 1. 요약할 부분과 유지할 부분으로 나눕니다.
        val messagesToSummarize = originalHistory.history.take(SUMMARY_SOURCE_MESSAGES)
        val recentMessages = originalHistory.history.drop(SUMMARY_SOURCE_MESSAGES)

        // 2. 요약을 위한 프롬프트를 생성합니다.
        val summarizationPrompt = """
            다음 대화는 챗봇의 이전 대화 기록입니다. 이 대화의 핵심 내용을 다른 AI 모델이 대화의 맥락을 이어갈 수 있도록 한두 문단으로 간결하게 요약해주세요.
            ---
            ${messagesToSummarize.joinToString("\n") { "[${it.role}]: ${it.text}" }}
            ---
            요약:
        """.trimIndent()

        // 3. Gemini API를 호출하여 요약문을 받습니다.
        val summaryText = geminiPort.summerizeContent(summarizationPrompt)

        // 요약에 실패했거나 내용이 없으면 원본을 유지하여 안정성을 높입니다.
        if (summaryText.isBlank()) {
            logger.warn("Summarization failed or returned empty. Skipping history modification.")
            return originalHistory
        }

        // 4. 요약된 메시지와 최신 메시지를 합쳐 새로운 대화 기록을 만듭니다.
        val summaryMessage = ChatMessage(role = "system", text = "이전 대화 요약: $summaryText")
        val newMessages = mutableListOf(summaryMessage)
        newMessages.addAll(recentMessages)

        // 5. 새로운 대화 기록을 담은 새 ChatHistory 객체를 반환합니다.
        return ChatHistory(originalHistory.userId, newMessages)
    }

    /**
     * 텍스트의 토큰 수를 간단하게 추정합니다. (정확하지 않으므로 보수적으로 계산)
     */
    private fun estimateTokens(text: String): Int {
        // 한글 1글자 ≈ 1.5~2.5 토큰, 영어 1단어 ≈ 1.3 토큰
        // 여기서는 간단하게 글자 수의 2배로 계산하여 넉넉하게 추정합니다.
        return text.length * 2
    }
}