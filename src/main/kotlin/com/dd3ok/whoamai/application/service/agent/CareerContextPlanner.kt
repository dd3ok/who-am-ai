package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.application.service.ContextRetriever
import com.dd3ok.whoamai.application.service.LLMRouter
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.domain.ChatMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CareerContextPlanner(
    private val llmRouter: LLMRouter,
    private val contextRetriever: ContextRetriever
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun plan(userPrompt: String, history: List<ChatMessage>): CareerPromptPlan {
        var contexts = contextRetriever.retrieveByRule(userPrompt)
        var resumeQuestionDetected = contexts.isNotEmpty()
        var retrievalPath = if (contexts.isNotEmpty()) "rule" else "unknown"

        if (contexts.isEmpty()) {
            val routeDecision = llmRouter.route(userPrompt, history)
            logger.info("No rule match. LLM Router hint: {}", routeDecision)
            if (routeDecision.queryType == QueryType.RESUME_RAG) {
                resumeQuestionDetected = true
                contexts = contextRetriever.retrieveByVector(userPrompt, routeDecision)
                retrievalPath = "vector"
            } else {
                retrievalPath = "non_rag"
            }
        }

        if (contexts.isEmpty() && resumeQuestionDetected) {
            retrievalPath = "rag_empty"
        }

        return CareerPromptPlan(
            history = history,
            userPrompt = userPrompt,
            contexts = contexts,
            useRagPrompt = contexts.isNotEmpty() || resumeQuestionDetected,
            retrievalPath = retrievalPath,
            resumeQuestionDetected = resumeQuestionDetected
        )
    }
}
