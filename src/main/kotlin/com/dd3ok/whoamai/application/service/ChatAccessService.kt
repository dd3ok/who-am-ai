package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatAccessStore
import com.dd3ok.whoamai.common.config.ChatAccessProperties
import com.dd3ok.whoamai.domain.ChatAccessException
import com.dd3ok.whoamai.domain.ChatSession
import com.dd3ok.whoamai.domain.ChatSessionGrant
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class ChatAccessService(
    private val store: ChatAccessStore,
    private val properties: ChatAccessProperties
) {
    private val random = SecureRandom()

    suspend fun issueSession(clientIp: String): ChatSessionGrant {
        val now = Instant.now()
        consume("sessions:global", properties.sessionsPerMinute, now)
        consume("sessions:ip:${hash(clientIp)}", properties.sessionsPerIpPerMinute, now)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val session = ChatSession(UUID.randomUUID().toString(), now.plusSeconds(properties.sessionLifetimeSeconds))
        store.createSession(hash(token), session)
        return ChatSessionGrant(session.conversationId, token, session.expiresAt)
    }

    suspend fun authorize(conversationId: String, token: String?, clientIp: String) {
        val now = Instant.now()
        // Count failed authentication too; opening another connection must not reset the budget.
        consume("messages:global", properties.messagesPerMinute, now)
        consume("messages:ip:${hash(clientIp)}", properties.messagesPerIpPerMinute, now)
        if (token == null || !TOKEN_PATTERN.matches(token)) throw ChatAccessException("SESSION_REQUIRED")
        val session = store.findSession(hash(token))
        if (session == null || session.conversationId != conversationId || !session.expiresAt.isAfter(now)) {
            throw ChatAccessException("SESSION_REQUIRED")
        }
        consume("messages:session:$conversationId", properties.messagesPerSessionPerMinute, now)
    }

    private suspend fun consume(key: String, limit: Int, now: Instant) {
        val minute = now.epochSecond / 60
        val count = store.incrementRequestCount("$key:$minute", Instant.ofEpochSecond((minute + 2) * 60))
        if (count > limit) throw ChatAccessException("RATE_LIMITED")
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")
    }
}
