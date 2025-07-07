package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.flow.Flow

interface GeminiPort {
    suspend fun generateChatContent(history: List<ChatMessage>): Flow<String>
    suspend fun generateContent(prompt: String, purpose: String): String
}