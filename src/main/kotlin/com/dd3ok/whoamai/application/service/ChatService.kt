package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatHistoryWindow
import com.dd3ok.whoamai.application.service.career.CareerContextPlanner
import com.dd3ok.whoamai.application.service.career.CareerPromptAssembler
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.ChatEvent
import com.dd3ok.whoamai.domain.ChatEventType
import com.dd3ok.whoamai.domain.ChatSource
import com.dd3ok.whoamai.domain.StreamMessage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
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
        // The character-based estimate is conservative; retain the user turn preceding an 8K-token answer.
        const val API_WINDOW_TOKENS = 65536
        const val PERSISTENCE_WINDOW_TOKENS = 131072
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> =
        chatEvents(message, requirePersistence = false).mapNotNull { if (it.type == ChatEventType.DELTA) it.content else null }

    override suspend fun streamChatEvents(message: StreamMessage): Flow<ChatEvent> =
        chatEvents(message, requirePersistence = true)

    private fun chatEvents(message: StreamMessage, requirePersistence: Boolean): Flow<ChatEvent> = flow {
        val userId = message.uuid
        val userPrompt = message.content
        val requestSample = Timer.start(meterRegistry)
        val firstTokenSample = Timer.start(meterRegistry)
        var stage = "history_load"
        var mode = "unknown"

        try {
            val domainHistory = recordSuspendTimer("whoamai.chat.history.load.duration") {
                loadHistory(userId)
            }
            val pastHistory = ChatHistoryWindow.recentMessages(domainHistory.history, API_WINDOW_TOKENS)
            stage = "retrieval"
            val plan = careerContextPlanner.plan(userPrompt, pastHistory)
            val finalHistory = careerPromptAssembler.assemble(plan.prompt)
            mode = if (plan.prompt.useRagPrompt) "rag" else "chat"
            meterRegistry.counter(
                "whoamai.chat.request.total", "mode", mode,
                "retrieval_path", plan.retrieval.retrievalPath
            ).increment()
            meterRegistry.summary("whoamai.rag.context.size").record(plan.prompt.contexts.size.toDouble())
            if (plan.retrieval.resumeQuestionDetected && plan.prompt.contexts.isEmpty()) {
                meterRegistry.counter("whoamai.rag.empty_context.total").increment()
            }

            emit(ChatEvent(ChatEventType.SOURCES, sources = plan.prompt.sources.map {
                ChatSource(it.chunkId, it.title, it.url)
            }))

            // 4. LLM 호출 및 결과 스트리밍
            stage = "gemini"
            val modelResponseBuilder = StringBuilder()
            val modelFirstTokenSample = Timer.start(meterRegistry)
            val modelTotalSample = Timer.start(meterRegistry)
            var firstTokenRecorded = false
            try {
                geminiPort.generateChatContent(finalHistory).collect { chunk ->
                    if (!firstTokenRecorded) {
                        modelFirstTokenSample.stop(meterRegistry.timer("whoamai.chat.gemini.first_token.duration"))
                        firstTokenSample.stop(meterRegistry.timer("whoamai.chat.first_token.duration"))
                        firstTokenRecorded = true
                    }
                    modelResponseBuilder.append(chunk)
                    emit(ChatEvent(ChatEventType.DELTA, content = chunk))
                }
            } finally {
                modelTotalSample.stop(meterRegistry.timer("whoamai.chat.gemini.total.duration"))
            }
            check(modelResponseBuilder.isNotBlank()) { "Model returned an empty answer." }
            stage = "history_save"
            try {
                saveHistory(userId, userPrompt, modelResponseBuilder.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requirePersistence) throw e
                logger.warn("Chat history save failed for legacy stream user {}.", userId, e)
            }
            emit(ChatEvent(ChatEventType.DONE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            meterRegistry.counter(
                "whoamai.chat.error.total", "stage", stage,
                "exception", e::class.java.simpleName, "mode", mode
            ).increment()
            throw e
        } finally {
            requestSample.stop(meterRegistry.timer("whoamai.chat.total.duration"))
        }
    }

    private suspend fun saveHistory(userId: String, userPrompt: String, modelResponse: String) {
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
