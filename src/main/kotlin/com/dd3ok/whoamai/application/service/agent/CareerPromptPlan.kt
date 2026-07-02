package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.domain.ChatMessage

data class CareerPromptPlan(
    val history: List<ChatMessage>,
    val userPrompt: String,
    val contexts: List<String>,
    val useRagPrompt: Boolean,
    val retrievalPath: String,
    val resumeQuestionDetected: Boolean
)
