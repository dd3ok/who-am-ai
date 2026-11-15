package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.service.ChatAccessService
import com.dd3ok.whoamai.domain.ChatAccessException
import com.dd3ok.whoamai.common.config.WebSocketProperties
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.StreamMessage
import com.dd3ok.whoamai.domain.ChatEvent
import com.dd3ok.whoamai.domain.ChatEventType
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.reactor.asFlux
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Component
class StreamChatWebSocketHandler(
    private val chatUseCase: ChatUseCase,
    private val objectMapper: ObjectMapper,
    private val accessService: ChatAccessService,
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
                // Reactor Netty owns and releases the received payload after this callback.
                val json = if (message.type != WebSocketMessage.Type.TEXT ||
                    message.payload.readableByteCount() > webSocketProperties.maxMessageBytes
                ) null else message.payloadAsText
                responseMessages(session, json)
            }

        return session.send(outbound)
    }

    private fun responseMessages(session: WebSocketSession, json: String?): Flux<WebSocketMessage> {
        val eventMode = session.handshakeInfo.uri.path == "/ws/chat/events"
        val requestId = UUID.randomUUID().toString()
        return flow {
            val userMessage = parseClientMessage(json ?: throw InvalidClientMessageException())
            validate(userMessage)
            withTimeout(webSocketProperties.responseTimeoutMs) {
                accessService.authorize(userMessage.uuid, userMessage.sessionToken,
                    session.handshakeInfo.remoteAddress?.address?.hostAddress ?: "unknown")
                val authorizedMessage = userMessage.copy(sessionToken = null)
                if (eventMode) {
                    chatUseCase.streamChatEvents(authorizedMessage).collect { event ->
                        emit(session.textMessage(objectMapper.writeValueAsString(event.copy(requestId = requestId))))
                    }
                } else {
                    chatUseCase.streamChatResponse(authorizedMessage).collect { emit(session.textMessage(it)) }
                }
            }
        }.asFlux().onErrorResume { e ->
            val code = when (e) {
                is InvalidClientMessageException -> "INVALID_MESSAGE"
                is TimeoutCancellationException -> "TIMEOUT"
                is ChatAccessException -> e.code
                else -> "TEMPORARY_ERROR"
            }
            val errorNotice = when (code) {
                "INVALID_MESSAGE" -> INVALID_MESSAGE_ERROR
                "SESSION_REQUIRED" -> "Chat session expired or invalid. Start a new session."
                "RATE_LIMITED" -> "Too many requests. Please wait a minute."
                "TIMEOUT" -> "The response is taking too long. Please try again."
                else -> TEMPORARY_ERROR
            }
            if (code == "TEMPORARY_ERROR") logger.error("Failed to process chat request {}", requestId, e)
            val payload = if (eventMode) objectMapper.writeValueAsString(
                ChatEvent(ChatEventType.ERROR, content = errorNotice, requestId = requestId, code = code)
            ) else errorPayload(
                code = if (code == "TIMEOUT") "MODEL_TIMEOUT" else code,
                message = errorNotice,
                retryAfterMs = if (code == "TIMEOUT") webSocketProperties.responseTimeoutMs else null
            )
            Flux.just(session.textMessage(payload))
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
        if (message.uuid.isBlank() || message.uuid.length > webSocketProperties.maxUuidLength ||
            message.content.isBlank() || message.content.length > webSocketProperties.maxContentLength ||
            message.type != MessageType.USER
        ) {
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
        private const val TEMPORARY_ERROR = "Temporary error. Please try again."
    }
}
