package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.service.ChatAccessService
import com.dd3ok.whoamai.application.service.fixedWindowStore
import com.dd3ok.whoamai.common.config.ChatAccessProperties
import com.dd3ok.whoamai.common.config.WebSocketProperties
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals

class ChatSessionControllerTest {
    @Test
    fun `session issuance rejects foreign origins and applies no store and rate limits`() {
        val controller = ChatSessionController(ChatAccessService(fixedWindowStore(),
            ChatAccessProperties(sessionsPerIpPerMinute = 1)), WebSocketProperties())
        val client = WebTestClient.bindToController(controller).build()
        client.post().uri("/api/chat/sessions").exchange().expectStatus().isForbidden
        client.post().uri("/api/chat/sessions").header("Origin", "https://evil.invalid").exchange().expectStatus().isForbidden
        client.post().uri("/api/chat/sessions").header("Origin", "https://dd3ok.github.io").exchange()
            .expectStatus().isCreated.expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.sessionToken").isNotEmpty.jsonPath("$.conversationId").isNotEmpty
        client.post().uri("/api/chat/sessions").header("Origin", "https://dd3ok.github.io").exchange()
            .expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "60")
    }

    @Test
    fun `rate limit uses forwarded client ip behind proxy and applies cooldown retry-after header`() = kotlinx.coroutines.test.runTest {
        val accessProps = ChatAccessProperties(
            sessionsPerIpPerMinute = 1,
            blockViolationThreshold = 2,
            blockDurationSeconds = 900
        )
        val controller = ChatSessionController(
            ChatAccessService(fixedWindowStore(), accessProps),
            WebSocketProperties(),
            ClientIpResolver(accessProps)
        )
        val proxyRemote = InetSocketAddress(InetAddress.getByName("100.64.0.10"), 443)

        suspend fun issueFrom(forwardedIp: String) = controller.create(
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/chat/sessions")
                    .header("Origin", "https://dd3ok.github.io")
                    .header("True-Client-IP", forwardedIp)
                    .remoteAddress(proxyRemote)
            ).request
        )

        assertEquals(201, issueFrom("203.0.113.10").statusCode.value())
        // First rate-limit rejection -> 60s
        val firstReject = issueFrom("203.0.113.10")
        assertEquals(429, firstReject.statusCode.value())
        assertEquals("60", firstReject.headers.getFirst("Retry-After"))
        // Violation threshold reached -> 900s cooldown
        val blockedReject = issueFrom("203.0.113.10")
        assertEquals(429, blockedReject.statusCode.value())
        assertEquals("900", blockedReject.headers.getFirst("Retry-After"))
        // Different client IP behind the same Render proxy still succeeds cleanly.
        assertEquals(201, issueFrom("203.0.113.20").statusCode.value())
    }
}
