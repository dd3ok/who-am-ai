package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.ChatSession
import java.time.Instant

interface ChatAccessStore {
    suspend fun createSession(tokenHash: String, session: ChatSession)
    suspend fun findSession(tokenHash: String): ChatSession?
    suspend fun incrementRequestCount(key: String, expiresAt: Instant): Long
    suspend fun findBlockExpiry(key: String): Instant? = null
    suspend fun setBlockExpiry(key: String, expiresAt: Instant) {}
}
