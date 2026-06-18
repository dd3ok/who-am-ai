package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import org.springframework.stereotype.Component

@Component
class CareerPromptAssembler(
    private val promptProvider: PromptProvider
) {
    fun assemble(plan: CareerPromptPlan): List<ChatMessage> {
        val finalUserPrompt = if (plan.useRagPrompt) {
            val context = if (plan.contexts.isNotEmpty()) {
                plan.contexts.joinToString("\n---\n")
            } else {
                "관련 정보 없음"
            }
            promptProvider.renderRagTemplate(context, plan.userPrompt)
        } else {
            promptProvider.renderConversationalTemplate(plan.userPrompt)
        }

        return plan.history + ChatMessage(role = "user", text = finalUserPrompt)
    }
}
