package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.service.ChatAccessService
import com.dd3ok.whoamai.application.service.InMemoryChatAccessStore
import com.dd3ok.whoamai.common.config.ChatAccessProperties
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.mock
import com.dd3ok.whoamai.common.config.WebSocketProperties
import com.dd3ok.whoamai.domain.StreamMessage
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
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
    private val accessService = mock(ChatAccessService::class.java)

    @Test
    fun `rejects disallowed origin before reading chat messages`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
        val session = RecordingWebSocketSession(
            origin = "https://example.invalid",
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

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
            accessService = accessService,
            webSocketProperties = WebSocketProperties(allowedOrigins = listOf("https://preview.example"))
        )
        val session = RecordingWebSocketSession(
            origin = "https://preview.example",
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        assertEquals(1, chatUseCase.messages.size)
        assertEquals(listOf("ok:user-1"), session.sentPayloads)
    }

    @Test
    fun `rejects null origin by default before reading chat messages`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
        val session = RecordingWebSocketSession(
            origin = null,
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        assertEquals(CloseStatus.POLICY_VIOLATION, session.closedWith)
        assertEquals(0, chatUseCase.messages.size)
        assertEquals(emptyList<String>(), session.sentPayloads)
    }

    @Test
    fun `allows null origin only when explicitly configured`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(
            chatUseCase = chatUseCase,
            objectMapper = jacksonObjectMapper(),
            accessService = accessService,
            webSocketProperties = WebSocketProperties(
                allowedOrigins = listOf("https://preview.example"),
                allowNullOrigin = true
            )
        )
        val session = RecordingWebSocketSession(
            origin = null,
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        assertEquals(1, chatUseCase.messages.size)
        assertEquals(listOf("ok:user-1"), session.sentPayloads)
    }

    @Test
    fun `rejects blank uuid message without calling chat use case`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf("""{"uuid":" ","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

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
            val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
            val session = RecordingWebSocketSession(
                origin = "https://dd3ok.github.io",
                incomingPayloads = listOf(payload)
            )

            handler.handle(session).block(Duration.ofSeconds(1))

            assertEquals(0, chatUseCase.messages.size)
            assertTrue(session.sentPayloads.single().contains("Invalid chat message"))
        }
    }

    @Test
    fun `rejects malformed json without calling chat use case`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf("{not-json")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        assertEquals(0, chatUseCase.messages.size)
        assertTrue(session.sentPayloads.single().contains("Invalid chat message"))
    }

    @Test
    fun `delayed first response returns structured timeout error`() {
        val chatUseCase = DelayedFirstResponseChatUseCase()
        val objectMapper = jacksonObjectMapper()
        val handler = StreamChatWebSocketHandler(
            chatUseCase = chatUseCase,
            objectMapper = objectMapper,
            accessService = accessService,
            webSocketProperties = WebSocketProperties(responseTimeoutMs = 10)
        )
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        val error = objectMapper.readValue<WebSocketErrorPayload>(session.sentPayloads.single())
        assertEquals("ERROR", error.type)
        assertEquals("MODEL_TIMEOUT", error.code)
        assertEquals(10L, error.retryAfterMs)
    }

    @Test
    fun `slow response setup returns structured timeout error`() {
        val chatUseCase = SlowBeforeFlowChatUseCase()
        val objectMapper = jacksonObjectMapper()
        val handler = StreamChatWebSocketHandler(
            chatUseCase = chatUseCase,
            objectMapper = objectMapper,
            accessService = accessService,
            webSocketProperties = WebSocketProperties(responseTimeoutMs = 10)
        )
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf("""{"uuid":"user-1","type":"USER","content":"hello"}""")
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        val error = objectMapper.readValue<WebSocketErrorPayload>(session.sentPayloads.single())
        assertEquals("ERROR", error.type)
        assertEquals("MODEL_TIMEOUT", error.code)
        assertEquals(10L, error.retryAfterMs)
    }

    @Test
    fun `uses a single outbound send stream for multiple client messages`() {
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf(
                """{"uuid":"user-1","type":"USER","content":"hello"}""",
                """{"uuid":"user-2","type":"USER","content":"hi"}"""
            )
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        assertEquals(1, session.sendCalls)
        assertEquals(listOf("ok:user-1", "ok:user-2"), session.sentPayloads)
    }

    @Test
    fun `processes multiple client messages sequentially even when first response is delayed`() {
        val chatUseCase = DelayedFirstResponseChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = listOf(
                """{"uuid":"user-1","type":"USER","content":"hello"}""",
                """{"uuid":"user-2","type":"USER","content":"hi"}"""
            )
        )

        handler.handle(session).block(Duration.ofSeconds(1))

        assertEquals(1, session.sendCalls)
        assertEquals(listOf("ok:user-1", "ok:user-2"), session.sentPayloads)
    }

    @Test
    fun `does not release inbound pooled data buffer owned by websocket runtime`() {
        val nettyFactory = NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT)
        val nativeBuffer = PooledByteBufAllocator.DEFAULT.buffer()
        nativeBuffer.writeBytes("""{"uuid":"user-1","type":"USER","content":"hello"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = nettyFactory.wrap(nativeBuffer)
        val chatUseCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(chatUseCase, jacksonObjectMapper(), accessService)
        val session = RecordingWebSocketSession(
            origin = "https://dd3ok.github.io",
            incomingPayloads = emptyList(),
            providedIncomingMessages = listOf(WebSocketMessage(WebSocketMessage.Type.TEXT, payload))
        )

        try {
            handler.handle(session).block(Duration.ofSeconds(1))

            assertEquals(1, payload.nativeBuffer.refCnt())
            assertEquals(listOf("ok:user-1"), session.sentPayloads)
        } finally {
            while (payload.nativeBuffer.refCnt() > 0) {
                payload.release()
            }
        }
    }

    @Test
    fun `event endpoint sends sources deltas and done with a shared request id`() {
        val mapper = jacksonObjectMapper()
        val useCase = object : ChatUseCase {
            override suspend fun streamChatResponse(message: StreamMessage) = error("Legacy path must not be used")
            override suspend fun streamChatEvents(message: StreamMessage) = flowOf(
                com.dd3ok.whoamai.domain.ChatEvent(com.dd3ok.whoamai.domain.ChatEventType.SOURCES,
                    sources = listOf(com.dd3ok.whoamai.domain.ChatSource("project_1", "프로젝트"))),
                com.dd3ok.whoamai.domain.ChatEvent(com.dd3ok.whoamai.domain.ChatEventType.DELTA, content = "답변"),
                com.dd3ok.whoamai.domain.ChatEvent(com.dd3ok.whoamai.domain.ChatEventType.DONE)
            )
        }
        val session = RecordingWebSocketSession("https://dd3ok.github.io",
            listOf("""{"uuid":"user-1","type":"USER","content":"질문"}"""), path = "/ws/chat/events")

        StreamChatWebSocketHandler(useCase, mapper, accessService).handle(session).block(Duration.ofSeconds(1))

        val events = session.sentPayloads.map { mapper.readTree(it) }
        assertEquals(listOf("SOURCES", "DELTA", "DONE"), events.map { it["type"].asText() })
        assertEquals("project_1", events.first()["sources"][0]["id"].asText())
        assertEquals(1, events.map { it["requestId"].asText() }.distinct().size)
        assertTrue(events.first()["requestId"].asText().isNotBlank())
    }

    @Test
    fun `event stream reports partial failure without a done event`() {
        val mapper = jacksonObjectMapper()
        val useCase = object : ChatUseCase {
            override suspend fun streamChatResponse(message: StreamMessage): Flow<String> = flow {
                emit("일부 응답")
                throw IllegalStateException("internal failure")
            }
        }
        val session = RecordingWebSocketSession("https://dd3ok.github.io",
            listOf("""{"uuid":"user-1","type":"USER","content":"질문"}"""), path = "/ws/chat/events")

        StreamChatWebSocketHandler(useCase, mapper, accessService).handle(session).block(Duration.ofSeconds(1))

        val events = session.sentPayloads.map { mapper.readTree(it) }
        assertEquals(listOf("DELTA", "ERROR"), events.map { it["type"].asText() })
        assertEquals("TEMPORARY_ERROR", events.last()["code"].asText())
        assertEquals(events.first()["requestId"], events.last()["requestId"])
        assertTrue(!session.sentPayloads.last().contains("internal failure"))
    }

    @Test
    fun `request deadline returns a timeout event`() {
        val mapper = jacksonObjectMapper()
        val session = RecordingWebSocketSession("https://dd3ok.github.io",
            listOf("""{"uuid":"user-1","type":"USER","content":"질문"}"""), path = "/ws/chat/events")
        StreamChatWebSocketHandler(DelayedFirstResponseChatUseCase(), mapper, accessService,
            WebSocketProperties(responseTimeoutMs = 10)).handle(session).block(Duration.ofSeconds(1))
        val event = mapper.readTree(session.sentPayloads.single())
        assertEquals("ERROR", event["type"].asText())
        assertEquals("TIMEOUT", event["code"].asText())
    }

    @Test
    fun `rejects oversized content uuid and frame before invoking the model`() {
        val mapper = jacksonObjectMapper()
        val cases = listOf(
            WebSocketProperties(maxContentLength = 3) to "hello",
            WebSocketProperties(maxUuidLength = 3) to "hi",
            WebSocketProperties(maxMessageBytes = 10) to "hi"
        )
        cases.forEach { (properties, content) ->
            val useCase = RecordingChatUseCase()
            val session = RecordingWebSocketSession("https://dd3ok.github.io",
                listOf(mapper.writeValueAsString(StreamMessage("user-1", com.dd3ok.whoamai.domain.MessageType.USER, content))))
            StreamChatWebSocketHandler(useCase, mapper, accessService, properties).handle(session).block(Duration.ofSeconds(1))
            assertTrue(useCase.messages.isEmpty())
            val error = mapper.readValue<WebSocketErrorPayload>(session.sentPayloads.single())
            assertEquals("ERROR", error.type)
            assertEquals("INVALID_MESSAGE", error.code)
            assertEquals("Invalid chat message.", error.message)
        }
    }

    @Test
    fun `both transports reject unowned history and enforce quota across connections`() = runBlocking {
        val access = ChatAccessService(InMemoryChatAccessStore(), ChatAccessProperties(messagesPerSessionPerMinute = 1))
        val grant = access.issueSession("unknown")
        val mapper = jacksonObjectMapper()
        val useCase = RecordingChatUseCase()
        val handler = StreamChatWebSocketHandler(useCase, mapper, access)
        fun send(path: String, uuid: String = grant.conversationId, token: String? = grant.sessionToken): RecordingWebSocketSession {
            val payload = mapper.writeValueAsString(StreamMessage(uuid, com.dd3ok.whoamai.domain.MessageType.USER, "hello", token))
            return RecordingWebSocketSession("https://dd3ok.github.io", listOf(payload), path = path).also {
                handler.handle(it).block(Duration.ofSeconds(1))
            }
        }
        assertTrue(send("/ws/chat", token = null).sentPayloads.single().contains("session"))
        assertEquals("SESSION_REQUIRED", mapper.readTree(send("/ws/chat/events", uuid = "victim").sentPayloads.single())["code"].asText())
        assertTrue(useCase.messages.isEmpty())
        send("/ws/chat/events")
        assertEquals(1, useCase.messages.size)
        assertEquals(null, useCase.messages.single().sessionToken)
        assertEquals("RATE_LIMITED", mapper.readTree(send("/ws/chat/events").sentPayloads.single())["code"].asText())
        assertEquals(1, useCase.messages.size)
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

    private class SlowBeforeFlowChatUseCase : ChatUseCase {
        override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
            delay(50)
            return flowOf("ok:${message.uuid}")
        }
    }

    private data class WebSocketErrorPayload(
        val type: String,
        val code: String,
        val message: String,
        val retryAfterMs: Long? = null
    )

    private class RecordingWebSocketSession(
        origin: String?,
        incomingPayloads: List<String>,
        providedIncomingMessages: List<WebSocketMessage>? = null,
        private val path: String = "/ws/chat"
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
            HandshakeInfo(URI.create("ws://localhost$path"), headers, Mono.empty(), null)

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
}
