package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.service.ChatAccessService
import com.dd3ok.whoamai.application.service.InMemoryChatAccessStore
import com.dd3ok.whoamai.common.config.ChatAccessProperties
import com.dd3ok.whoamai.common.config.WebSocketProperties
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class ChatSessionControllerTest {
    @Test
    fun `session issuance rejects foreign origins and applies no store and rate limits`() {
        val controller = ChatSessionController(ChatAccessService(InMemoryChatAccessStore(),
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
}
