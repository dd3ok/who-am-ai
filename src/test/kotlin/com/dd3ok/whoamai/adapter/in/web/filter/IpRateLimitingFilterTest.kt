package com.dd3ok.whoamai.adapter.`in`.web.filter

import com.dd3ok.whoamai.application.service.RateLimiterService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.InetSocketAddress

class IpRateLimitingFilterTest {

    @Test
    fun `forwarded header is not trusted by default`() {
        val filter = IpRateLimitingFilter(
            rateLimiterService = RateLimiterService(maxRequests = 1, windowInMinutes = 1),
            bypassParamName = "",
            bypassParamValue = ""
        )

        val firstChain = RecordingWebFilterChain()
        val first = postAiFitting(xForwardedFor = "203.0.113.10", remoteHost = "10.0.0.1")
        filter.filter(first, firstChain).block()

        val secondChain = RecordingWebFilterChain()
        val second = postAiFitting(xForwardedFor = "203.0.113.11", remoteHost = "10.0.0.1")
        filter.filter(second, secondChain).block()

        assertTrue(firstChain.wasCalled)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.response.statusCode)
    }

    @Test
    fun `trusted forwarded header uses first forwarded address`() {
        val filter = IpRateLimitingFilter(
            rateLimiterService = RateLimiterService(maxRequests = 1, windowInMinutes = 1),
            bypassParamName = "",
            bypassParamValue = "",
            trustForwardedHeaders = true
        )

        val firstChain = RecordingWebFilterChain()
        val first = postAiFitting(xForwardedFor = "203.0.113.10, 10.0.0.1", remoteHost = "10.0.0.1")
        filter.filter(first, firstChain).block()

        val secondChain = RecordingWebFilterChain()
        val second = postAiFitting(xForwardedFor = "203.0.113.10, 10.0.0.2", remoteHost = "10.0.0.2")
        filter.filter(second, secondChain).block()

        assertTrue(firstChain.wasCalled)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.response.statusCode)
    }

    private fun postAiFitting(xForwardedFor: String, remoteHost: String): MockServerWebExchange {
        val request = MockServerHttpRequest
            .post("/api/ai-fitting")
            .header("X-Forwarded-For", xForwardedFor)
            .remoteAddress(InetSocketAddress(remoteHost, 12345))
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
