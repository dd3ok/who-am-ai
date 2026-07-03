package com.dd3ok.whoamai.domain

object ChatHistoryWindow {
    fun recentMessages(
        messages: List<ChatMessage>,
        maxTokens: Int,
        includeAtLeastOne: Boolean = false
    ): List<ChatMessage> {
        var currentTokens = 0
        val recentMessages = mutableListOf<ChatMessage>()
        for (msg in messages.reversed()) {
            val estimatedTokens = estimateTokens(msg.text)
            if (currentTokens + estimatedTokens > maxTokens && (recentMessages.isNotEmpty() || !includeAtLeastOne)) {
                break
            }
            recentMessages.add(msg)
            currentTokens += estimatedTokens
        }
        recentMessages.reverse()
        return recentMessages
    }

    fun trim(userId: String, messages: List<ChatMessage>, maxTokens: Int): ChatHistory =
        ChatHistory(userId = userId, messages = recentMessages(messages, maxTokens, includeAtLeastOne = true))

    private fun estimateTokens(text: String): Int = (text.length * 1.5).toInt()
}
