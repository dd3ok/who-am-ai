package com.dd3ok.whoamai.application.port.out

import kotlinx.coroutines.flow.Flow

interface GeminiPort {
    suspend fun generateStreamingContent(prompt: String): Flow<String>
}