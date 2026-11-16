package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.service.ContextRetriever
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.port.out.ConversationTopic
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
        val resolved = resolveQuery(userPrompt, history)
        val retrievalPrompt = resolved.query
        var sources = recordSuspendTimer("whoamai.chat.rag.rule.duration") {
            resolved.topic?.let { contextRetriever.retrieveSourcesForTopic(it) }
                ?: contextRetriever.retrieveSourcesByRule(retrievalPrompt)
        }
        var resumeQuestionDetected = resolved.topic != null || sources.isNotEmpty()
        var retrievalPath = if (sources.isNotEmpty()) "rule" else "unknown"

        if (sources.isEmpty() && resolved.topic == null) {
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

    private suspend fun resolveQuery(userPrompt: String, history: List<ChatMessage>): ResolvedQuery {
        val known = llmRouter.resolveQuery(userPrompt, history)
        // Assistant prose cannot establish a topic or supply facts. Bound the extra model input to user turns.
        val recent = history.filter { it.role == "user" }.takeLast(6)
        val topics = contextRetriever.conversationTopics(userPrompt, recent)
        if (topics.isEmpty()) return ResolvedQuery(known)
        // Keep the existing fast path only when its target still identifies a concrete candidate.
        if (known != userPrompt && topics.any { known == "${it.title} — $userPrompt" }) return ResolvedQuery(known)
        val selected = recordSuspendTimer("whoamai.chat.topic.resolve.duration") {
            conversationTopicPort.selectTopic(userPrompt, recent, topics)
        } ?: return ResolvedQuery(userPrompt)
        check(selected in topics) { "Conversation topic must come from user-established candidates." }
        return ResolvedQuery("${selected.title} — $userPrompt", selected)
    }

    private data class ResolvedQuery(val query: String, val topic: ConversationTopic? = null)

    private suspend fun <T> recordSuspendTimer(name: String, block: suspend () -> T): T {
        val sample = Timer.start(meterRegistry)
        return try {
            block()
        } finally {
            sample.stop(meterRegistry.timer(name))
        }
    }
}
