package com.dd3ok.whoamai.adapter.`in`.web.filter

import com.dd3ok.whoamai.application.service.RateLimiterService
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
@Order(1)
class IpRateLimitingFilter(
    private val rateLimiterService: RateLimiterService,
    @Value("\${rate-limit.bypass-parameter.name:}") private val bypassParamName: String,
    @Value("\${rate-limit.bypass-parameter.value:}") private val bypassParamValue: String,
    @Value("\${rate-limit.trust-forwarded-headers:false}") private val trustForwardedHeaders: Boolean = false
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request

        if (request.uri.path.startsWith("/api/ai-fitting")) {
            // 설정값이 존재하고, 요청 파라미터와 일치하는 경우 우회
            if (bypassParamName.isNotBlank() && request.queryParams.getFirst(bypassParamName) == bypassParamValue) {
                return chain.filter(exchange)
            }

            val ip = resolveClientIp(exchange)

            if (ip == "unknown") {
                // IP를 알 수 없는 경우 요청 거부
                exchange.response.statusCode = HttpStatus.FORBIDDEN
                return exchange.response.setComplete()
            }

            if (!rateLimiterService.isAllowed(ip)) {
                // 제한 횟수를 초과한 경우 429 Too Many Requests 응답
                exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                return exchange.response.setComplete()
            }
        }

        // 허용된 경우 다음 필터 또는 핸들러로 요청 전달
        return chain.filter(exchange)
    }

    private fun resolveClientIp(exchange: ServerWebExchange): String {
        val request = exchange.request
        if (trustForwardedHeaders) {
            request.headers.getFirst("X-Forwarded-For")
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return request.remoteAddress?.address?.hostAddress ?: "unknown"
    }
}
