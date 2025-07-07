package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.common.config.PromptProperties
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.StreamMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ChatService(
    private val geminiPort: GeminiPort,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val llmRouter: LLMRouter, // QueryClassifier -> LLMRouter
    private val contextRetriever: ContextRetriever,
    private val promptProperties: PromptProperties
) : ChatUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val API_WINDOW_TOKENS = 2048
        const val SUMMARY_TRIGGER_TOKENS = 4096
        const val SUMMARY_SOURCE_MESSAGES = 5
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        val userId = message.uuid
        val userPrompt = message.content

        val domainHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
        val pastHistory = createApiHistoryWindow(domainHistory)

        // 1. 의도 분류 -> LLM 라우터에 위임
        val routeDecision = llmRouter.route(userPrompt)
        logger.info("LLM Router decision: $routeDecision")

        // 2. 프롬프트 생성
        val finalHistory = if (routeDecision.queryType == QueryType.RESUME_RAG) {
            logger.info("Query classified as RESUME_RAG. Retrieving context...")
            // 2-1. 컨텍스트 검색 (ContextRetriever 위임)
            val relevantContexts = contextRetriever.retrieve(userPrompt, routeDecision)
            createRagPrompt(pastHistory, userPrompt, relevantContexts)
        } else {
            logger.info("Query classified as NON_RAG. Proceeding with conversational prompt.")
            createConversationalPrompt(pastHistory, userPrompt)
        }

        // 3. LLM 호출 및 결과 스트리밍
        val modelResponseBuilder = StringBuilder()
        return geminiPort.generateChatContent(finalHistory)
            .onEach { chunk -> modelResponseBuilder.append(chunk) }
            .onCompletion { cause ->
                if (cause == null) {
                    val fullResponse = modelResponseBuilder.toString()
                    // 4. 대화 기록 저장
                    saveHistory(userId, userPrompt, fullResponse)
                } else {
                    logger.error("Chat stream failed with cause. History NOT saved.", cause)
                }
            }
    }

    private suspend fun saveHistory(userId: String, userPrompt: String, modelResponse: String) {
        val currentHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
        currentHistory.addMessage(ChatMessage(role = "user", text = userPrompt))
        currentHistory.addMessage(ChatMessage(role = "model", text = modelResponse))
        val finalHistoryToSave = summarizeHistoryIfNeeded(currentHistory)
        chatHistoryRepository.save(finalHistoryToSave)
        logger.info("[SUCCESS] History for user {} saved.", userId)
    }

    private fun createRagPrompt(history: List<ChatMessage>, userPrompt: String, contexts: List<String>): List<ChatMessage> {
        val contextString = if (contexts.isNotEmpty()) contexts.joinToString("\n---\n") else "관련 정보 없음"
        val finalUserPrompt = promptProperties.ragTemplate
            .replace("{context}", contextString)
            .replace("{question}", userPrompt)

        return history + ChatMessage(role = "user", text = finalUserPrompt)
    }

    private fun createConversationalPrompt(history: List<ChatMessage>, userPrompt: String): List<ChatMessage> {
        val finalUserPrompt = promptProperties.conversationalTemplate
            .replace("{question}", userPrompt)

        return history + ChatMessage(role = "user", text = finalUserPrompt)
    }

    private fun createApiHistoryWindow(domainHistory: ChatHistory): List<ChatMessage> {
        var currentTokens = 0
        val recentMessages = mutableListOf<ChatMessage>()
        for (msg in domainHistory.history.reversed()) {
            val estimatedTokens = estimateTokens(msg.text)
            if (currentTokens + estimatedTokens > API_WINDOW_TOKENS) {
                break
            }
            recentMessages.add(msg)
            currentTokens += estimatedTokens
        }
        recentMessages.reverse()
        return recentMessages
    }

    private suspend fun summarizeHistoryIfNeeded(originalHistory: ChatHistory): ChatHistory {
        val totalTokens = originalHistory.history.sumOf { estimateTokens(it.text) }
        if (totalTokens < SUMMARY_TRIGGER_TOKENS || originalHistory.history.size < SUMMARY_SOURCE_MESSAGES + 2) {
            return originalHistory
        }

        logger.info("History for user {} exceeds token threshold. Starting summarization.", originalHistory.userId)
        val messagesToSummarize = originalHistory.history.take(SUMMARY_SOURCE_MESSAGES)
        val recentMessages = originalHistory.history.drop(SUMMARY_SOURCE_MESSAGES)
        val summarizationPrompt = """다음 대화의 핵심 내용을 한두 문단으로 간결하게 요약해주세요. --- ${messagesToSummarize.joinToString("\n") { "[${it.role}]: ${it.text}" }} --- 요약:""".trimIndent()
        val summaryText = geminiPort.summerizeContent(summarizationPrompt)

        if (summaryText.isBlank()) {
            logger.warn("Summarization failed or returned empty. Skipping history modification.")
            return originalHistory
        }
        val summaryMessage = ChatMessage(role = "model", text = "이전 대화 요약: $summaryText")
        val newMessages = mutableListOf(summaryMessage).apply { addAll(recentMessages) }
        return ChatHistory(originalHistory.userId, newMessages)
    }

    private fun estimateTokens(text: String): Int = (text.length * 1.5).toInt()
}