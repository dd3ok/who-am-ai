package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.service.ContextRetriever
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.service.LLMRouter
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.domain.ChatMessage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CareerContextPlanner(
    private val llmRouter: LLMRouter,
    private val contextRetriever: ContextRetriever,
    private val meterRegistry: MeterRegistry,
    private val conversationTopicPort: ConversationTopicPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun plan(userPrompt: String, history: List<ChatMessage>): CareerContextPlan {
        val retrievalPrompt = resolveQuery(userPrompt, history)
        var sources = recordSuspendTimer("whoamai.chat.rag.rule.duration") {
            contextRetriever.retrieveSourcesByRule(retrievalPrompt)
        }
        var resumeQuestionDetected = sources.isNotEmpty()
        var retrievalPath = if (sources.isNotEmpty()) "rule" else "unknown"

        if (sources.isEmpty()) {
            val routeDecision = llmRouter.route(retrievalPrompt)
            logger.info("No rule match. LLM Router hint: {}", routeDecision)
            if (routeDecision.queryType == QueryType.RESUME_RAG) {
                resumeQuestionDetected = true
                sources = recordSuspendTimer("whoamai.chat.rag.vector.duration") {
                    contextRetriever.retrieveSourcesByVector(retrievalPrompt, routeDecision)
                }
                retrievalPath = "vector"
            } else {
                retrievalPath = "non_rag"
            }
        }

        if (sources.isEmpty() && resumeQuestionDetected) {
            retrievalPath = "rag_empty"
        }

        return CareerContextPlan(
            prompt = CareerPromptInput(
                history = history,
                userPrompt = if (sources.isNotEmpty() || resumeQuestionDetected) retrievalPrompt else userPrompt,
                contexts = sources.map { it.content },
                useRagPrompt = sources.isNotEmpty() || resumeQuestionDetected,
                sources = sources
            ),
            retrieval = CareerRetrievalSummary(
                retrievalPath = retrievalPath,
                resumeQuestionDetected = resumeQuestionDetected
            )
        )
    }

    private suspend fun resolveQuery(userPrompt: String, history: List<ChatMessage>): String {
        val known = llmRouter.resolveQuery(userPrompt, history)
        if (known != userPrompt) return known
        // Assistant prose cannot establish a topic or supply facts. Bound the extra model input to user turns.
        val recent = history.filter { it.role == "user" }.takeLast(6)
        val topics = llmRouter.followUpTopics(userPrompt, recent)
        if (topics.isEmpty()) return userPrompt
        val selected = recordSuspendTimer("whoamai.chat.topic.resolve.duration") {
            conversationTopicPort.selectTopic(userPrompt, recent, topics)
        } ?: return userPrompt
        check(selected in topics) { "Conversation topic must come from user-established candidates." }
        return "$selected — $userPrompt"
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
