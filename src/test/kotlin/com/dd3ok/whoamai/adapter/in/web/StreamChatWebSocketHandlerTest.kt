package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.common.config.WebSocketProperties
import com.dd3ok.whoamai.domain.StreamMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.netty.buffer.PooledByteBufAllocator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.Function

class StreamChatWebSocketHandlerTest {

    @Test
    fun `rejects disallowed origin before reading chat messages`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper())
        val session = RecordingWebSocketSession(
            origin = "https://example.invalid",
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(BLOCK_TIMEOUT)

        assertEquals(CloseStatus.POLICY_VIOLATION, session.closedWith)
        assertEquals(0, chatUseCase.messages.size)
        assertEquals(emptyList<String>(), session.sentPayloads)
    }

    @Test
    fun `allows origins supplied by configuration`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(
            chatUseCase = chatUseCase,
            objectMapper = jacksonObjectMapper(),
            webSocketProperties = WebSocketProperties(allowedOrigins = listOf("https://preview.example"))
        )
        val session = RecordingWebSocketSession(
            origin = "https://preview.example",
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(BLOCK_TIMEOUT)

        assertEquals(1, chatUseCase.messages.size)
        assertEquals(listOf("ok:user-1"), session.sentPayloads)
    }

    @Test
    fun `rejects blank uuid message without calling chat use case`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper())
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf("""{"uuid":" ","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(BLOCK_TIMEOUT)

        assertEquals(0, chatUseCase.messages.size)
        assertTrue(session.sentPayloads.single().contains("Invalid chat message"))
    }

    @Test
    fun `rejects blank content and non user messages without calling chat use case`() {
        val invalidPayloads = listOf(
            """{"uuid":"user-1","type":"USER","content":" "}""",
            """{"uuid":"user-1","type":"AI","content":"hello"}"""
        )

        invalidPayloads.forEach { payload ->
            val chatUseCase = RecordingChatUseCase()
            val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper())
            val session = RecordingWebSocketSession(
                origin = "https://dd3ok.github.io",
                incomingPayloads = listOf(payload)
            )

            handler.handle(session).block(BLOCK_TIMEOUT)

            assertEquals(0, chatUseCase.messages.size)
            assertTrue(session.sentPayloads.single().contains("Invalid chat message"))
        }
    }

    @Test
    fun `rejects malformed json without calling chat use case`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper())
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf("{not-json")
        )

        handler.handle(session).block(BLOCK_TIMEOUT)

        assertEquals(0, chatUseCase.messages.size)
        assertTrue(session.sentPayloads.single().contains("Invalid chat message"))
    }

    @Test
    fun `uses a single outbound send stream for multiple client messages`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper())
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf(
                """{"uuid":"user-1","type":"USER","content":"hello"}""",
                """{"uuid":"user-2","type":"USER","content":"hi"}"""
            )
        )

        handler.handle(session).block(BLOCK_TIMEOUT)

        assertEquals(1, session.sendCalls)
        assertEquals(listOf("ok:user-1", "ok:user-2"), session.sentPayloads)
    }

    @Test
    fun `processes multiple client messages sequentially even when first response is delayed`() {
        val chatUseCase = DelayedFirstResponseChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper())
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf(
                """{"uuid":"user-1","type":"USER","content":"hello"}""",
                """{"uuid":"user-2","type":"USER","content":"hi"}"""
            )
        )

        handler.handle(session).block(BLOCK_TIMEOUT)

        assertEquals(1, session.sendCalls)
        assertEquals(listOf("ok:user-1", "ok:user-2"), session.sentPayloads)
    }

    @Test
    fun `releases inbound pooled data buffer after extracting payload`() {
        val nettyFactory = NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT)
        val nativeBuffer = PooledByteBufAllocator.DEFAULT.buffer()
        nativeBuffer.writeBytes("""{"uuid":"user-1","type":"USER","content":"hello"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = nettyFactory.wrap(nativeBuffer)
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper())
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = emptyList(),
            providedIncomingMessages = listOf(WebSocketMessage(WebSocketMessage.Type.TEXT, payload))
        )

        try {
            handler.handle(session).block(BLOCK_TIMEOUT)

            assertEquals(0, payload.nativeBuffer.refCnt())
            assertEquals(listOf("ok:user-1"), session.sentPayloads)
        } finally {
            while (payload.nativeBuffer.refCnt() > 0) {
                payload.release()
            }
        }
    }

    private class RecordingChatUseCase : ChatUseCase {
        val messages = mutableListOf<StreamMessage>()

        override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
            messages += message
            return flowOf("ok:${message.uuid}")
        }
    }

    private class DelayedFirstResponseChatUseCase : ChatUseCase {
        override suspend fun streamChatResponse(message: StreamMessage): Flow<String> = flow {
            if (message.uuid == "user-1") {
                delay(50)
            }
            emit("ok:${message.uuid}")
        }
    }

    private class RecordingWebSocketSession(
        origin: String?,
        incomingPayloads: List<String>,
        providedIncomingMessages: List<WebSocketMessage>? = null
    ) : WebSocketSession {
        private val dataBufferFactory = DefaultDataBufferFactory()
        private val headers = HttpHeaders()
        private val incomingMessages = providedIncomingMessages ?: incomingPayloads.map(::textMessage)

        val sentPayloads = mutableListOf<String>()
        var closedWith: CloseStatus? = null
        var sendCalls: Int = 0

        init {
            origin?.let { headers.add(HttpHeaders.ORIGIN, it) }
        }

        override fun getId(): String = "test-session"

        override fun getHandshakeInfo(): HandshakeInfo =
            HandshakeInfo(URI.create("ws://localhost/ws/chat"), headers, Mono.empty(), null)

        override fun bufferFactory(): DataBufferFactory = dataBufferFactory

        override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()

        override fun receive(): Flux<WebSocketMessage> = Flux.fromIterable(incomingMessages)

        override fun send(messages: Publisher<WebSocketMessage>): Mono<Void> =
            Flux.from(messages)
                .doOnSubscribe { sendCalls += 1 }
                .doOnNext { sentPayloads += it.payloadAsText }
                .then()

        override fun isOpen(): Boolean = closedWith == null

        override fun close(status: CloseStatus): Mono<Void> {
            closedWith = status
            return Mono.empty()
        }

        override fun closeStatus(): Mono<CloseStatus> =
            closedWith?.let { Mono.just(it) } ?: Mono.empty()

        override fun textMessage(payload: String): WebSocketMessage =
            WebSocketMessage(
                WebSocketMessage.Type.TEXT,
                dataBufferFactory.wrap(payload.toByteArray(StandardCharsets.UTF_8))
            )

        override fun binaryMessage(payloadFactory: Function<DataBufferFactory, DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(dataBufferFactory))

        override fun pingMessage(payloadFactory: Function<DataBufferFactory, DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(dataBufferFactory))

        override fun pongMessage(payloadFactory: Function<DataBufferFactory, DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(dataBufferFactory))
    }

    private companion object {
        val BLOCK_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
