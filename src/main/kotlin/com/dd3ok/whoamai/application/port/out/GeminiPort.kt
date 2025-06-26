package com.dd3ok.whoamai.application.port.out

import com.google.genai.types.Content
import kotlinx.coroutines.flow.Flow

interface GeminiPort {
    suspend fun generateChatContent(history: List<Content>): Flow<String>
    suspend fun summerizeContent(prompt: String): String
}