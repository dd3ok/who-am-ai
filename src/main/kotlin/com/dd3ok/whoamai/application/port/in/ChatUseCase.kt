package com.dd3ok.whoamai.application.port.`in`

import com.dd3ok.whoamai.domain.StreamMessage
import kotlinx.coroutines.flow.Flow

interface ChatUseCase {
    suspend fun streamChatResponse(message: StreamMessage): Flow<String>
}