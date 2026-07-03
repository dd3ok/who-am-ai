package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import org.springframework.stereotype.Component

@Component
class CareerPromptAssembler(
    private val promptProvider: PromptProvider
) {
    fun assemble(input: CareerPromptInput): List<ChatMessage> {
        val finalUserPrompt = if (input.useRagPrompt) {
            val context = if (input.contexts.isNotEmpty()) {
                input.contexts.joinToString("\n---\n")
            } else {
                "관련 정보 없음"
            }
            promptProvider.renderRagTemplate(context, input.userPrompt)
        } else {
            promptProvider.renderConversationalTemplate(input.userPrompt)
        }

        return input.history + ChatMessage(role = "user", text = finalUserPrompt)
    }
}
