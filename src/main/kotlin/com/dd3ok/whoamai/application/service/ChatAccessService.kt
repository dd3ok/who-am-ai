package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatAccessStore
import com.dd3ok.whoamai.common.config.ChatAccessProperties
import com.dd3ok.whoamai.domain.ChatAccessException
import com.dd3ok.whoamai.domain.ChatSession
import com.dd3ok.whoamai.domain.ChatSessionGrant
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.UUID

@Service
class ChatAccessService internal constructor(
    private val store: ChatAccessStore,
    private val properties: ChatAccessProperties,
    private val clock: Clock
) {
    @Autowired
    constructor(store: ChatAccessStore, properties: ChatAccessProperties) : this(store, properties, Clock.systemUTC())

    private val random = SecureRandom()
    private val blockedIpCache: MutableMap<String, Instant> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Instant>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>): Boolean =
                size > MAX_BLOCK_CACHE_ENTRIES
        }
    )

    suspend fun assertNotBlocked(clientIp: String) {
        if (properties.blockDurationSeconds <= 0L) return
        val now = clock.instant()
        val ipHash = hash(clientIp)
        checkBlocked(ipHash, now)
    }

    suspend fun issueSession(clientIp: String): ChatSessionGrant {
        val now = clock.instant()
        val ipHash = hash(clientIp)
        checkBlocked(ipHash, now)
        consumeIpMinute("sessions:ip:$ipHash", properties.sessionsPerIpPerMinute, ipHash, now)
        consumeGlobal("sessions:global", properties.sessionsPerMinute, now)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val session = ChatSession(UUID.randomUUID().toString(), now.plusSeconds(properties.sessionLifetimeSeconds))
        store.createSession(hash(token), session)
        return ChatSessionGrant(session.conversationId, token, session.expiresAt)
    }

    suspend fun authorize(conversationId: String, token: String?, clientIp: String) {
        val now = clock.instant()
        val ipHash = hash(clientIp)
        checkBlocked(ipHash, now)
        // Count failed authentication too, but reject throttled/blocked IPs before charging the shared budget.
        consumeIpMinute("messages:ip:$ipHash", properties.messagesPerIpPerMinute, ipHash, now)
        consumeIpDay("messages:ip_day:$ipHash", properties.messagesPerIpPerDay, ipHash, now)
        consumeGlobal("messages:global", properties.messagesPerMinute, now)
        if (token == null || !TOKEN_PATTERN.matches(token)) throw ChatAccessException("SESSION_REQUIRED")
        val session = store.findSession(hash(token))
        if (session == null || session.conversationId != conversationId || !session.expiresAt.isAfter(now)) {
            throw ChatAccessException("SESSION_REQUIRED")
        }
        consumeGlobal("messages:session:$conversationId", properties.messagesPerSessionPerMinute, now)
    }

    private suspend fun checkBlocked(ipHash: String, now: Instant) {
        if (properties.blockDurationSeconds <= 0L) return
        val cachedExpiry = synchronized(blockedIpCache) { blockedIpCache[ipHash] }
        if (cachedExpiry != null) {
            if (cachedExpiry.isAfter(now)) {
                throw ChatAccessException("RATE_LIMITED", retryAfterSeconds(now, cachedExpiry))
            }
            blockedIpCache.remove(ipHash)
        }

        val storedExpiry = store.findBlockExpiry("block:ip:$ipHash") ?: return
        if (storedExpiry.isAfter(now)) {
            blockedIpCache[ipHash] = storedExpiry
            throw ChatAccessException("RATE_LIMITED", retryAfterSeconds(now, storedExpiry))
        }
    }

    private suspend fun consumeIpMinute(key: String, limit: Int, ipHash: String, now: Instant) {
        if (limit <= 0) return
        val minute = now.epochSecond / MINUTE_SECONDS
        val expiresAt = Instant.ofEpochSecond((minute + 2) * MINUTE_SECONDS)
        val count = store.incrementRequestCount("$key:$minute", expiresAt)
        if (count > limit) {
            recordIpViolationAndThrow(ipHash, now, MINUTE_SECONDS)
        }
    }

    private suspend fun consumeIpDay(key: String, limit: Int, ipHash: String, now: Instant) {
        if (limit <= 0) return
        val day = now.epochSecond / DAY_SECONDS
        val expiresAt = Instant.ofEpochSecond((day + 2) * DAY_SECONDS)
        val count = store.incrementRequestCount("$key:$day", expiresAt)
        if (count > limit) {
            val defaultRetryAfter = ((day + 1) * DAY_SECONDS - now.epochSecond).coerceAtLeast(MINUTE_SECONDS)
            recordIpViolationAndThrow(ipHash, now, defaultRetryAfter)
        }
    }

    private suspend fun consumeGlobal(key: String, limit: Int, now: Instant) {
        if (limit <= 0) return
        val minute = now.epochSecond / MINUTE_SECONDS
        val count = store.incrementRequestCount("$key:$minute", Instant.ofEpochSecond((minute + 2) * MINUTE_SECONDS))
        if (count > limit) {
            throw ChatAccessException("RATE_LIMITED", MINUTE_SECONDS)
        }
    }

    private suspend fun recordIpViolationAndThrow(ipHash: String, now: Instant, defaultRetryAfter: Long): Nothing {
        if (properties.blockViolationThreshold > 0 && properties.blockDurationSeconds > 0L) {
            val windowSeconds = properties.blockViolationWindowSeconds.coerceAtLeast(60L)
            val window = now.epochSecond / windowSeconds
            val violations = store.incrementRequestCount(
                "violations:ip:$ipHash:$window",
                Instant.ofEpochSecond((window + 2) * windowSeconds)
            )
            if (violations >= properties.blockViolationThreshold) {
                val blockUntil = now.plusSeconds(properties.blockDurationSeconds)
                blockedIpCache[ipHash] = blockUntil
                store.setBlockExpiry("block:ip:$ipHash", blockUntil)
                throw ChatAccessException("RATE_LIMITED", properties.blockDurationSeconds)
            }
        }
        throw ChatAccessException("RATE_LIMITED", defaultRetryAfter)
    }

    private fun retryAfterSeconds(now: Instant, expiresAt: Instant): Long =
        Duration.between(now, expiresAt).seconds.coerceAtLeast(1L)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MINUTE_SECONDS = 60L
        private const val DAY_SECONDS = 86400L
        private const val MAX_BLOCK_CACHE_ENTRIES = 1024
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")
    }
}
