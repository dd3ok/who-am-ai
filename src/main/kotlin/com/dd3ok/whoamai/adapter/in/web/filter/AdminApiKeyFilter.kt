package com.dd3ok.whoamai.adapter.`in`.web.filter

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
@Order(0)
class AdminApiKeyFilter(
    @Value("\${admin.api-key:}") private val adminApiKey: String
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!exchange.request.uri.path.isAdminPath()) {
            return chain.filter(exchange)
        }

        if (adminApiKey.isBlank()) {
            exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
            return exchange.response.setComplete()
        }

        val requestApiKey = exchange.request.headers.getFirst(ADMIN_API_KEY_HEADER)
        if (requestApiKey == null || !constantTimeEquals(adminApiKey, requestApiKey)) {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        return chain.filter(exchange)
    }

    private fun String.isAdminPath(): Boolean =
        this == "/api/admin" || startsWith("/api/admin/")

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8)
        )

    companion object {
        const val ADMIN_API_KEY_HEADER = "X-Admin-Api-Key"
    }
}
