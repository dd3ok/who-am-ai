package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.domain.ChatMessage

data class CareerContextPlan(
    val prompt: CareerPromptInput,
    val retrieval: CareerRetrievalSummary
)

data class CareerPromptInput(
    val history: List<ChatMessage>,
    val userPrompt: String,
    val contexts: List<String>,
    val useRagPrompt: Boolean
)

data class CareerRetrievalSummary(
    val retrievalPath: String,
    val resumeQuestionDetected: Boolean
)
