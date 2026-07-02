package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.service.agent.CareerContextPlanner
import com.dd3ok.whoamai.application.service.agent.CareerPromptAssembler
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
    private val careerContextPlanner: CareerContextPlanner,
    private val careerPromptAssembler: CareerPromptAssembler,
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

        val plan = careerContextPlanner.plan(userPrompt, pastHistory)
        val finalHistory = careerPromptAssembler.assemble(plan)
        if (plan.useRagPrompt) {
            if (plan.contexts.isNotEmpty()) {
                logger.info("Context found. Proceeding with RAG prompt.")
            } else {
                logger.info("Resume intent detected but context empty. Proceeding with grounded empty-context RAG prompt.")
            }
        } else {
            logger.info("No context found. Proceeding with conversational prompt.")
        }
        meterRegistry.counter(
            "whoamai.chat.request.total",
            "mode", if (plan.useRagPrompt) "rag" else "chat",
            "retrieval_path", plan.retrievalPath
        ).increment()
        meterRegistry.summary("whoamai.rag.context.size").record(plan.contexts.size.toDouble())
        if (plan.resumeQuestionDetected && plan.contexts.isEmpty()) {
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
