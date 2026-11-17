package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatAccessStore
import com.dd3ok.whoamai.common.config.ChatAccessProperties
import com.dd3ok.whoamai.domain.ChatAccessException
import com.dd3ok.whoamai.domain.ChatSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatAccessServiceTest {
    @Test
    fun `IP rejected session requests leave the global allowance for other clients`() = runTest {
        val service = ChatAccessService(fixedWindowStore(), ChatAccessProperties(
            sessionsPerIpPerMinute = 1, sessionsPerMinute = 3
        ))
        service.issueSession("ip-a")
        repeat(3) {
            assertEquals("RATE_LIMITED", assertFailsWith<ChatAccessException> { service.issueSession("ip-a") }.code)
        }

        service.issueSession("ip-b")
        service.issueSession("ip-c")
        assertEquals("RATE_LIMITED", assertFailsWith<ChatAccessException> { service.issueSession("ip-d") }.code)
    }

    @Test
    fun `IP rejected messages leave the shared allowance for other clients`() = runTest {
        val store = fixedWindowStore()
        val properties = ChatAccessProperties(messagesPerIpPerMinute = 1, messagesPerMinute = 2)
        val first = ChatAccessService(store, properties)
        val second = ChatAccessService(store, properties)
        val a = first.issueSession("ip-a")
        val b = second.issueSession("ip-b")
        val c = second.issueSession("ip-c")
        first.authorize(a.conversationId, a.sessionToken, "ip-a")
        repeat(3) {
            assertEquals("RATE_LIMITED", assertFailsWith<ChatAccessException> {
                first.authorize(a.conversationId, null, "ip-a")
            }.code)
        }

        second.authorize(b.conversationId, b.sessionToken, "ip-b")
        assertEquals("RATE_LIMITED", assertFailsWith<ChatAccessException> {
            second.authorize(c.conversationId, c.sessionToken, "ip-c")
        }.code)
    }

    @Test
    fun `token is hashed at rest and bound to an unexpired conversation`() = runTest {
        val store = InMemoryChatAccessStore()
        val service = ChatAccessService(store, ChatAccessProperties())
        val grant = service.issueSession("127.0.0.1")
        assertFalse(store.sessions.containsKey(grant.sessionToken))
        service.authorize(grant.conversationId, grant.sessionToken, "127.0.0.1")
        for ((id, token) in listOf(grant.conversationId to null, "another" to grant.sessionToken,
            grant.conversationId to "x".repeat(43))) {
            assertEquals("SESSION_REQUIRED", assertFailsWith<ChatAccessException> { service.authorize(id, token, "127.0.0.1") }.code)
        }
        store.sessions.replaceAll { _, session -> session.copy(expiresAt = Instant.EPOCH) }
        assertEquals("SESSION_REQUIRED", assertFailsWith<ChatAccessException> {
            service.authorize(grant.conversationId, grant.sessionToken, "127.0.0.1")
        }.code)
    }

    @Test
    fun `separate application instances share global quotas`() = runTest {
        val store = InMemoryChatAccessStore()
        val properties = ChatAccessProperties(messagesPerMinute = 1, sessionsPerMinute = 2)
        val first = ChatAccessService(store, properties)
        val second = ChatAccessService(store, properties)
        val a = first.issueSession("ip-a")
        val b = second.issueSession("ip-b")
        assertEquals("RATE_LIMITED", assertFailsWith<ChatAccessException> { first.issueSession("ip-c") }.code)
        first.authorize(a.conversationId, a.sessionToken, "ip-a")
        assertEquals("RATE_LIMITED", assertFailsWith<ChatAccessException> {
            second.authorize(b.conversationId, b.sessionToken, "ip-b")
        }.code)
    }

    @Test
    fun `new sessions cannot bypass per ip request limits`() = runTest {
        val service = ChatAccessService(InMemoryChatAccessStore(), ChatAccessProperties(messagesPerIpPerMinute = 1, sessionsPerIpPerMinute = 2))
        val a = service.issueSession("ip")
        val b = service.issueSession("ip")
        assertFailsWith<ChatAccessException> { service.issueSession("ip") }
        service.authorize(a.conversationId, a.sessionToken, "ip")
        assertEquals("RATE_LIMITED", assertFailsWith<ChatAccessException> { service.authorize(b.conversationId, b.sessionToken, "ip") }.code)
    }

    @Test
    fun `daily message quota caps sustained requests across different minutes`() = runTest {
        var now = Instant.parse("2026-11-17T00:00:00Z")
        val clock = MutableClock(now) { now }
        val store = InMemoryChatAccessStore()
        val service = ChatAccessService(store, ChatAccessProperties(
            sessionLifetimeSeconds = 172800,
            messagesPerIpPerMinute = 5,
            messagesPerIpPerDay = 3,
            messagesPerSessionPerMinute = 10,
            blockViolationThreshold = 0
        ), clock)

        val grant = service.issueSession("ip-daily")
        repeat(3) { minuteIndex ->
            now = Instant.parse("2026-11-17T00:0${minuteIndex}:00Z")
            service.authorize(grant.conversationId, grant.sessionToken, "ip-daily")
        }

        now = Instant.parse("2026-11-17T00:10:00Z")
        val error = assertFailsWith<ChatAccessException> {
            service.authorize(grant.conversationId, grant.sessionToken, "ip-daily")
        }
        assertEquals("RATE_LIMITED", error.code)
        assertTrue(error.retryAfterSeconds >= 60L)

        // Next UTC day resets the daily quota cleanly.
        now = Instant.parse("2026-11-18T00:00:00Z")
        service.authorize(grant.conversationId, grant.sessionToken, "ip-daily")
    }

    @Test
    fun `repeated violations trigger a temporary cooldown block that expires and survives restarts`() = runTest {
        var now = Instant.parse("2026-11-17T00:00:00Z")
        val clock = MutableClock(now) { now }
        val store = InMemoryChatAccessStore()
        val props = ChatAccessProperties(
            sessionsPerIpPerMinute = 1,
            sessionsPerMinute = 10,
            blockViolationThreshold = 3,
            blockViolationWindowSeconds = 300,
            blockDurationSeconds = 900
        )
        val service = ChatAccessService(store, props, clock)

        service.issueSession("attacker-ip")
        repeat(2) {
            val err = assertFailsWith<ChatAccessException> { service.issueSession("attacker-ip") }
            assertEquals("RATE_LIMITED", err.code)
            assertEquals(60L, err.retryAfterSeconds)
        }

        val triggered = assertFailsWith<ChatAccessException> { service.issueSession("attacker-ip") }
        assertEquals("RATE_LIMITED", triggered.code)
        assertEquals(900L, triggered.retryAfterSeconds)

        // Subsequent requests fail at assertNotBlocked without touching global budget, even on a restarted instance.
        val restarted = ChatAccessService(store, props, clock)
        now = now.plusSeconds(100)
        val blockedErr = assertFailsWith<ChatAccessException> { restarted.assertNotBlocked("attacker-ip") }
        assertEquals(800L, blockedErr.retryAfterSeconds)
        val sessionBlocked = assertFailsWith<ChatAccessException> { restarted.issueSession("attacker-ip") }
        assertEquals(800L, sessionBlocked.retryAfterSeconds)

        // Healthy IP still gets service even while attacker is blocked.
        service.issueSession("clean-ip")

        // Block automatically lifts once TTL expires.
        now = now.plusSeconds(801)
        service.assertNotBlocked("attacker-ip")
    }

    @Test
    fun `daily quota cooldown preserves full remaining day instead of shortening Retry-After`() = runTest {
        var now = Instant.parse("2026-11-17T12:00:00Z")
        val clock = MutableClock(now) { now }
        val store = InMemoryChatAccessStore()
        val service = ChatAccessService(store, ChatAccessProperties(
            messagesPerIpPerMinute = 10,
            messagesPerIpPerDay = 1,
            messagesPerSessionPerMinute = 10,
            blockViolationThreshold = 2,
            blockDurationSeconds = 900
        ), clock)

        val grant = service.issueSession("daily-ip")
        service.authorize(grant.conversationId, grant.sessionToken, "daily-ip")

        val firstReject = assertFailsWith<ChatAccessException> {
            service.authorize(grant.conversationId, grant.sessionToken, "daily-ip")
        }
        assertEquals(43200L, firstReject.retryAfterSeconds)

        val secondReject = assertFailsWith<ChatAccessException> {
            service.authorize(grant.conversationId, grant.sessionToken, "daily-ip")
        }
        assertEquals(43200L, secondReject.retryAfterSeconds)
    }
}

private class MutableClock(
    private val initial: Instant,
    private val provider: () -> Instant
) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = provider()
}

// Same-window quota scenarios must not depend on crossing a wall-clock minute during execution.
internal fun fixedWindowStore(): ChatAccessStore {
    val store = InMemoryChatAccessStore()
    return object : ChatAccessStore by store {
        override suspend fun incrementRequestCount(key: String, expiresAt: Instant) =
            store.incrementRequestCount(key.substringBeforeLast(':'), expiresAt)
    }
}

class InMemoryChatAccessStore : ChatAccessStore {
    val sessions = ConcurrentHashMap<String, ChatSession>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val blocks = ConcurrentHashMap<String, Instant>()
    override suspend fun createSession(tokenHash: String, session: ChatSession) { sessions[tokenHash] = session }
    override suspend fun findSession(tokenHash: String) = sessions[tokenHash]
    override suspend fun incrementRequestCount(key: String, expiresAt: Instant) = counters.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    override suspend fun findBlockExpiry(key: String): Instant? = blocks[key]
    override suspend fun setBlockExpiry(key: String, expiresAt: Instant) { blocks[key] = expiresAt }
}
