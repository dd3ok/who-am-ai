package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.StreamMessage
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ChatService(
    private val geminiPort: GeminiPort,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val llmRouter: LLMRouter,
    private val contextRetriever: ContextRetriever,
    private val promptTemplateService: PromptProvider,
    private val meterRegistry: MeterRegistry
) : ChatUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val API_WINDOW_TOKENS = 2048
        const val PERSISTENCE_WINDOW_TOKENS = 8192
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        val userId = message.uuid
        val userPrompt = message.content

        val domainHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
        val pastHistory = createApiHistoryWindow(domainHistory)

        // 1. ContextRetriever를 먼저 호출하여 규칙 기반 검색을 시도
        var relevantContexts = contextRetriever.retrieveByRule(userPrompt)
        var resumeQuestionDetected = relevantContexts.isNotEmpty()
        var retrievalPath = if (relevantContexts.isNotEmpty()) "rule" else "unknown"

        // 2. 규칙 기반으로 컨텍스트를 찾지 못했다면, 그 때 LLM 라우터와 벡터 검색을 사용
        if (relevantContexts.isEmpty()) {
            val routeDecision = llmRouter.route(userPrompt, pastHistory)
            logger.info("No rule match. LLM Router hint: $routeDecision")
            if (routeDecision.queryType == QueryType.RESUME_RAG) {
                resumeQuestionDetected = true
                relevantContexts = contextRetriever.retrieveByVector(userPrompt, routeDecision)
                retrievalPath = "vector"
            } else {
                retrievalPath = "non_rag"
            }
        }
        if (relevantContexts.isEmpty() && resumeQuestionDetected) {
            retrievalPath = "rag_empty"
        }

        // 3. 최종적으로 컨텍스트 존재 여부에 따라 프롬프트 결정
        val useRagPrompt = relevantContexts.isNotEmpty()
        val finalHistory = if (useRagPrompt) {
            logger.info("Context found. Proceeding with RAG prompt.")
            createRagPrompt(pastHistory, userPrompt, relevantContexts)
        } else {
            logger.info("No context found. Proceeding with conversational prompt.")
            createConversationalPrompt(pastHistory, userPrompt)
        }
        meterRegistry.counter(
            "whoamai.chat.request.total",
            "mode", if (useRagPrompt) "rag" else "chat",
            "retrieval_path", retrievalPath
        ).increment()
        meterRegistry.summary("whoamai.rag.context.size").record(relevantContexts.size.toDouble())
        if (resumeQuestionDetected && relevantContexts.isEmpty()) {
            meterRegistry.counter("whoamai.rag.empty_context.total").increment()
        }

        // 4. LLM 호출 및 결과 스트리밍
        val modelResponseBuilder = StringBuilder()
        return geminiPort.generateChatContent(finalHistory)
            .onEach { chunk -> modelResponseBuilder.append(chunk) }
            .onCompletion { cause ->
                if (cause == null) {
                    val fullResponse = modelResponseBuilder.toString()
                    // 5. 대화 기록 저장
                    saveHistory(userId, userPrompt, fullResponse)
                } else {
                    logger.error("Chat stream failed with cause. History NOT saved.", cause)
                }
            }
    }

    private suspend fun saveHistory(userId: String, userPrompt: String, modelResponse: String) {
        val currentHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
        val messagesToPersist = currentHistory.history +
            listOf(
                ChatMessage(role = "user", text = userPrompt),
                ChatMessage(role = "model", text = modelResponse)
            )
        chatHistoryRepository.save(trimHistoryForPersistence(userId, messagesToPersist))
        logger.info("[SUCCESS] History for user {} saved.", userId)
    }

    private fun createRagPrompt(history: List<ChatMessage>, userPrompt: String, contexts: List<String>): List<ChatMessage> {
        val contextString = if (contexts.isNotEmpty()) contexts.joinToString("\n---\n") else "관련 정보 없음"
        val finalUserPrompt = promptTemplateService.renderRagTemplate(contextString, userPrompt)

        return history + ChatMessage(role = "user", text = finalUserPrompt)
    }

    private fun createConversationalPrompt(history: List<ChatMessage>, userPrompt: String): List<ChatMessage> {
        val finalUserPrompt = promptTemplateService.renderConversationalTemplate(userPrompt)

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

    private fun trimHistoryForPersistence(userId: String, messages: List<ChatMessage>): ChatHistory {
        var currentTokens = 0
        val recentMessages = mutableListOf<ChatMessage>()
        for (msg in messages.reversed()) {
            val estimatedTokens = estimateTokens(msg.text)
            if (currentTokens + estimatedTokens > PERSISTENCE_WINDOW_TOKENS && recentMessages.isNotEmpty()) {
                break
            }
            recentMessages.add(msg)
            currentTokens += estimatedTokens
        }
        recentMessages.reverse()
        return ChatHistory(userId = userId, messages = recentMessages)
    }

    private fun estimateTokens(text: String): Int = (text.length * 1.5).toInt()
}
