package com.dd3ok.whoamai.adapter.`in`.web.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class AdminApiKeyFilterTest {

    @Test
    fun `admin request fails closed when api key property is blank`() {
        val chain = RecordingWebFilterChain()
        val exchange = post("/api/admin/resume/reindex", apiKey = "secret")
        val filter = AdminApiKeyFilter(adminApiKey = "")

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.response.statusCode)
        assertFalse(chain.wasCalled)
    }

    @Test
    fun `admin request without api key header is unauthorized`() {
        val chain = RecordingWebFilterChain()
        val exchange = post("/api/admin/resume/reindex")
        val filter = AdminApiKeyFilter(adminApiKey = "secret")

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
        assertFalse(chain.wasCalled)
    }

    @Test
    fun `admin request with wrong api key header is unauthorized`() {
        val chain = RecordingWebFilterChain()
        val exchange = post("/api/admin/resume/reindex", apiKey = "wrong")
        val filter = AdminApiKeyFilter(adminApiKey = "secret")

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
        assertFalse(chain.wasCalled)
    }

    @Test
    fun `admin request with matching api key continues filter chain`() {
        val chain = RecordingWebFilterChain()
        val exchange = post("/api/admin/resume/reindex", apiKey = "secret")
        val filter = AdminApiKeyFilter(adminApiKey = "secret")

        filter.filter(exchange, chain).block()

        assertEquals(null, exchange.response.statusCode)
        assertTrue(chain.wasCalled)
    }

    @Test
    fun `non admin request continues filter chain without api key`() {
        val chain = RecordingWebFilterChain()
        val exchange = post("/api/chat")
        val filter = AdminApiKeyFilter(adminApiKey = "")

        filter.filter(exchange, chain).block()

        assertEquals(null, exchange.response.statusCode)
        assertTrue(chain.wasCalled)
    }

    private fun post(path: String, apiKey: String? = null): MockServerWebExchange {
        val request = MockServerHttpRequest
            .post(path)
            .apply {
                if (apiKey != null) {
                    header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, apiKey)
                }
            }
            .build()

        return MockServerWebExchange.from(request)
    }

    private class RecordingWebFilterChain : WebFilterChain {
        var wasCalled = false

        override fun filter(exchange: ServerWebExchange): Mono<Void> {
            wasCalled = true
            return Mono.empty()
        }
    }
}
