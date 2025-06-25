package com.dd3ok.whoamai.infrastructure.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.domain.StreamMessage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

@Component
class StreamChatWebSocketHandler(
    private val chatUseCase: ChatUseCase,
    private val objectMapper: ObjectMapper
) : WebSocketHandler {

    override fun handle(session: WebSocketSession): Mono<Void> = mono {
        handleChatSession(session)
    }.then()

    private suspend fun handleChatSession(session: WebSocketSession) {
        session.receive()
            .map { it.payloadAsText }
            .asFlow()
            .collect { json ->  // 각 메시지를 순차적으로 처리
                val userMessage = objectMapper.readValue(json, StreamMessage::class.java)

                val responseFlow = chatUseCase.streamChatResponse(userMessage)
                    .map { aiToken -> session.textMessage(aiToken) }
                    .asFlux()

                session.send(responseFlow).awaitFirstOrNull() // 완료까지 대기
            }
    }
}