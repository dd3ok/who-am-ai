package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.service.career.CareerContextPlanner
import com.dd3ok.whoamai.application.service.career.CareerPromptAssembler
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatHistoryWindow
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.StreamMessage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.coroutines.cancellation.CancellationException

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

        val domainHistory = recordSuspendTimer("whoamai.chat.history.load.duration") {
            loadHistory(userId)
        }
        val pastHistory = ChatHistoryWindow.recentMessages(domainHistory.history, API_WINDOW_TOKENS)

        val plan = careerContextPlanner.plan(userPrompt, pastHistory)
        val finalHistory = careerPromptAssembler.assemble(plan.prompt)
        if (plan.prompt.useRagPrompt) {
            if (plan.prompt.contexts.isNotEmpty()) {
                logger.info("Context found. Proceeding with RAG prompt.")
            } else {
                logger.info("Resume intent detected but context empty. Proceeding with grounded empty-context RAG prompt.")
            }
        } else {
            logger.info("No context found. Proceeding with conversational prompt.")
        }
        meterRegistry.counter(
            "whoamai.chat.request.total",
            "mode", if (plan.prompt.useRagPrompt) "rag" else "chat",
            "retrieval_path", plan.retrieval.retrievalPath
        ).increment()
        meterRegistry.summary("whoamai.rag.context.size").record(plan.prompt.contexts.size.toDouble())
        if (plan.retrieval.resumeQuestionDetected && plan.prompt.contexts.isEmpty()) {
            meterRegistry.counter("whoamai.rag.empty_context.total").increment()
        }

        val modelResponseBuilder = StringBuilder()
        var firstTokenSample: Timer.Sample? = null
        var totalSample: Timer.Sample? = null
        var firstTokenRecorded = false
        return geminiPort.generateChatContent(finalHistory)
            .onStart {
                modelResponseBuilder.clear()
                firstTokenRecorded = false
                firstTokenSample = Timer.start(meterRegistry)
                totalSample = Timer.start(meterRegistry)
            }
            .onEach { chunk ->
                if (!firstTokenRecorded) {
                    firstTokenSample?.stop(meterRegistry.timer("whoamai.chat.gemini.first_token.duration"))
                    firstTokenRecorded = true
                }
                modelResponseBuilder.append(chunk)
            }
            .onCompletion { cause ->
                totalSample?.stop(meterRegistry.timer("whoamai.chat.gemini.total.duration"))
                if (cause == null) {
                    val fullResponse = modelResponseBuilder.toString()
                    saveHistory(userId, userPrompt, fullResponse)
                } else if (cause is CancellationException) {
                    logger.info("Chat stream cancelled. History NOT saved.")
                } else {
                    meterRegistry.counter(
                        "whoamai.chat.error.total",
                        "stage", "gemini",
                        "exception", cause::class.java.simpleName,
                        "mode", if (plan.prompt.useRagPrompt) "rag" else "chat"
                    ).increment()
                    logger.error("Chat stream failed with cause. History NOT saved.", cause)
                }
            }
    }

    private suspend fun saveHistory(userId: String, userPrompt: String, modelResponse: String) {
        try {
            recordSuspendTimer("whoamai.chat.history.save.duration") {
                chatHistoryRepository.appendMessages(
                    userId = userId,
                    messages = listOf(
                        ChatMessage(role = "user", text = userPrompt),
                        ChatMessage(role = "model", text = modelResponse)
                    ),
                    maxTokens = PERSISTENCE_WINDOW_TOKENS
                )
            }
            logger.info("[SUCCESS] History for user {} saved.", userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Chat history save failed. Continuing without persistence for user {}.", userId, e)
        }
    }

    private suspend fun loadHistory(userId: String): ChatHistory {
        return try {
            chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Chat history lookup failed. Continuing without prior history for user {}.", userId, e)
            ChatHistory(userId = userId)
        }
    }

    private suspend fun <T> recordSuspendTimer(name: String, block: suspend () -> T): T {
        val sample = Timer.start(meterRegistry)
        return try {
            block()
        } finally {
            sample.stop(meterRegistry.timer(name))
        }
    }
}
