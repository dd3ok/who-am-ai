package com.dd3ok.whoamai.application.port.`in`

import com.dd3ok.whoamai.domain.StreamMessage
import com.dd3ok.whoamai.domain.ChatEvent
import com.dd3ok.whoamai.domain.ChatEventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

interface ChatUseCase {
    suspend fun streamChatResponse(message: StreamMessage): Flow<String>

    suspend fun streamChatEvents(message: StreamMessage): Flow<ChatEvent> = flow {
        streamChatResponse(message).collect { emit(ChatEvent(ChatEventType.DELTA, content = it)) }
        emit(ChatEvent(ChatEventType.DONE))
    }
}
