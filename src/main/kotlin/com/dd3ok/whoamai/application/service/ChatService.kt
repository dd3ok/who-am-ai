package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.domain.StreamMessage
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service

@Service
class ChatService(
    private val geminiPort: GeminiPort
) : ChatUseCase {

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        // 현재는 단순히 전달하지만, 향후 여기에 복잡한 비즈니스 로직 추가 가능
        // (예: 대화 이력 관리, 사용자 인증, 메시지 필터링 등)
        return geminiPort.generateStreamingContent(message.content)
    }
}