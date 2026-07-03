package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatHistoryWindow
import com.dd3ok.whoamai.domain.ChatMessage

interface ChatHistoryRepository {
    suspend fun findByUserId(userId: String): ChatHistory?
    suspend fun save(chatHistory: ChatHistory): ChatHistory

    suspend fun appendMessages(userId: String, messages: List<ChatMessage>, maxTokens: Int): ChatHistory {
        val currentHistory = findByUserId(userId) ?: ChatHistory(userId = userId)
        return save(ChatHistoryWindow.trim(userId, currentHistory.history + messages, maxTokens))
    }
}
