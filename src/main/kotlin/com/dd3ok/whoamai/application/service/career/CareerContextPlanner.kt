package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.service.ContextRetriever
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
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun plan(userPrompt: String, history: List<ChatMessage>): CareerContextPlan {
        var contexts = recordSuspendTimer("whoamai.chat.rag.rule.duration") {
            contextRetriever.retrieveByRule(userPrompt)
        }
        var resumeQuestionDetected = contexts.isNotEmpty()
        var retrievalPath = if (contexts.isNotEmpty()) "rule" else "unknown"

        if (contexts.isEmpty()) {
            val routeDecision = llmRouter.route(userPrompt, history)
            logger.info("No rule match. LLM Router hint: {}", routeDecision)
            if (routeDecision.queryType == QueryType.RESUME_RAG) {
                resumeQuestionDetected = true
                contexts = recordSuspendTimer("whoamai.chat.rag.vector.duration") {
                    contextRetriever.retrieveByVector(userPrompt, routeDecision)
                }
                retrievalPath = "vector"
            } else {
                retrievalPath = "non_rag"
            }
        }

        if (contexts.isEmpty() && resumeQuestionDetected) {
            retrievalPath = "rag_empty"
        }

        return CareerContextPlan(
            prompt = CareerPromptInput(
                history = history,
                userPrompt = userPrompt,
                contexts = contexts,
                useRagPrompt = contexts.isNotEmpty() || resumeQuestionDetected
            ),
            retrieval = CareerRetrievalSummary(
                retrievalPath = retrievalPath,
                resumeQuestionDetected = resumeQuestionDetected
            )
        )
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
