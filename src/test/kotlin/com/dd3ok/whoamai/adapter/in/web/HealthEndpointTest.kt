package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.service.DeepHealthMongoProbe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertNotNull

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.ai.openai.api-key=dummy",
        "spring.ai.google.genai.api-key=dummy",
        "spring.ai.google.genai.embedding.api-key=dummy",
        "spring.mongodb.uri=mongodb://192.0.2.1:27017/whoam_ai?serverSelectionTimeoutMS=1000&connectTimeoutMS=1000&socketTimeoutMS=1000",
        "admin.api-key=dummy"
    ]
)
class HealthEndpointTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mongoProbeProvider: ObjectProvider<DeepHealthMongoProbe>

    @Test
    fun `MongoDB diagnostics are registered even when the database is unavailable`() {
        assertNotNull(mongoProbeProvider.ifAvailable)
    }

    @Test
    fun `actuator health stays up when MongoDB is unavailable`() {
        val webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$port")
            .build()

        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
    }
}
