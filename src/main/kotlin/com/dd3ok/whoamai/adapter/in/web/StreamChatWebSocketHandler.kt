package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.domain.StreamMessage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

@Component
class StreamChatWebSocketHandler(
    private val chatUseCase: ChatUseCase,
    private val objectMapper: ObjectMapper
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(session: WebSocketSession): Mono<Void> = mono {
        handleChatSession(session)
    }.then()

    private suspend fun handleChatSession(session: WebSocketSession) {
        session.receive()
            .map { it.payloadAsText }
            .asFlow()
            .collect { json ->
                try {
                    val userMessage = objectMapper.readValue(json, StreamMessage::class.java)

                    val responseFlow = chatUseCase.streamChatResponse(userMessage)
                        .map { aiToken -> session.textMessage(aiToken) }
                        .asFlux()

                    session.send(responseFlow).awaitFirstOrNull()
                } catch (e: Exception) {
                    logger.error("Failed to process message from session ${session.id}: ${e.message}", e)
                    // session.send(Mono.just(session.textMessage("Invalid message format."))).awaitFirstOrNull()
                }
            }
    }
}