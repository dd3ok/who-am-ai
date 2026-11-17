package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.service.ChatAccessService
import com.dd3ok.whoamai.common.config.WebSocketProperties
import com.dd3ok.whoamai.domain.ChatAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ChatSessionController(
    private val accessService: ChatAccessService,
    private val properties: WebSocketProperties,
    private val clientIpResolver: ClientIpResolver = ClientIpResolver()
) {
    @PostMapping("/api/chat/sessions")
    suspend fun create(request: ServerHttpRequest): ResponseEntity<Any> {
        val origin = request.headers.origin
        if (if (origin == null) !properties.allowNullOrigin else origin !in properties.allowedOrigins) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        return try {
            val clientIp = clientIpResolver.resolve(request.headers, request.remoteAddress)
            val session = accessService.issueSession(clientIp)
            ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(session)
        } catch (e: ChatAccessException) {
            ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", e.retryAfterSeconds.toString())
                .cacheControl(CacheControl.noStore()).body(mapOf("code" to e.code))
        }
    }
}
