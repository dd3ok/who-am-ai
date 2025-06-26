package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.ChatHistory

interface ChatHistoryRepository {
    suspend fun findByUserId(userId: String): ChatHistory?
    suspend fun save(chatHistory: ChatHistory): ChatHistory
}