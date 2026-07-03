package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.common.config.WebSocketProperties
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.StreamMessage
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeoutException

@Component
class StreamChatWebSocketHandler(
    private val chatUseCase: ChatUseCase,
    private val objectMapper: ObjectMapper,
    private val webSocketProperties: WebSocketProperties = WebSocketProperties()
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(session: WebSocketSession): Mono<Void> {
        if (!isAllowedOrigin(session)) {
            logger.warn("Rejected WebSocket session ${session.id} from origin ${session.handshakeInfo.headers.origin}")
            return session.close(CloseStatus.POLICY_VIOLATION)
        }

        val outbound = session.receive()
            .concatMap { message ->
                val json = message.payloadAsText
                responseMessages(session, json)
            }

        return session.send(outbound)
    }

    private fun responseMessages(session: WebSocketSession, json: String): Flux<WebSocketMessage> {
        return mono {
            val userMessage = parseClientMessage(json)
            validate(userMessage)
            chatUseCase.streamChatResponse(userMessage)
        }
            .flatMapMany { responseFlow ->
                responseFlow
                    .map { aiToken -> session.textMessage(aiToken) }
                    .asFlux()
            }
            .timeout(Duration.ofMillis(webSocketProperties.responseTimeoutMs))
            .onErrorResume { e ->
                val errorNotice = when (e) {
                    is InvalidClientMessageException -> errorPayload(
                        code = "INVALID_MESSAGE",
                        message = INVALID_MESSAGE_ERROR
                    )
                    is TimeoutException -> errorPayload(
                        code = "MODEL_TIMEOUT",
                        message = MODEL_TIMEOUT_ERROR,
                        retryAfterMs = webSocketProperties.responseTimeoutMs
                    )
                    else -> {
                        logger.error("Failed to process message from session ${session.id}: ${e.message}", e)
                        errorPayload(
                            code = "TEMPORARY_ERROR",
                            message = TEMPORARY_ERROR
                        )
                    }
                }
                Flux.just(session.textMessage(errorNotice))
            }
    }

    private fun parseClientMessage(json: String): StreamMessage {
        return try {
            objectMapper.readValue(json, StreamMessage::class.java)
        } catch (e: JacksonException) {
            throw InvalidClientMessageException(e)
        }
    }

    private fun validate(message: StreamMessage) {
        if (message.uuid.isBlank() || message.content.isBlank() || message.type != MessageType.USER) {
            throw InvalidClientMessageException()
        }
    }

    private fun isAllowedOrigin(session: WebSocketSession): Boolean {
        val origin = session.handshakeInfo.headers.origin ?: return webSocketProperties.allowNullOrigin
        return origin in webSocketProperties.allowedOrigins
    }

    private class InvalidClientMessageException(cause: Throwable? = null) : RuntimeException(cause)

    private fun errorPayload(code: String, message: String, retryAfterMs: Long? = null): String {
        return objectMapper.writeValueAsString(
            WebSocketErrorPayload(
                code = code,
                message = message,
                retryAfterMs = retryAfterMs
            )
        )
    }

    private data class WebSocketErrorPayload(
        val type: MessageType = MessageType.ERROR,
        val code: String,
        val message: String,
        val retryAfterMs: Long? = null
    )

    companion object {
        private const val INVALID_MESSAGE_ERROR = "Invalid chat message."
        private const val MODEL_TIMEOUT_ERROR = "The response is taking too long. Please try again."
        private const val TEMPORARY_ERROR = "Temporary error. Please try again."
    }
}
