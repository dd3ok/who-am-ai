package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatAccessStore
import com.dd3ok.whoamai.common.config.ChatAccessProperties
import com.dd3ok.whoamai.domain.ChatAccessException
import com.dd3ok.whoamai.domain.ChatSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChatAccessServiceTest {
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
}

class InMemoryChatAccessStore : ChatAccessStore {
    val sessions = ConcurrentHashMap<String, ChatSession>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    override suspend fun createSession(tokenHash: String, session: ChatSession) { sessions[tokenHash] = session }
    override suspend fun findSession(tokenHash: String) = sessions[tokenHash]
    override suspend fun incrementRequestCount(key: String, expiresAt: Instant) = counters.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
}
