package com.dd3ok.whoamai.domain

import java.time.Instant

data class ChatSession(val conversationId: String, val expiresAt: Instant)

data class ChatSessionGrant(val conversationId: String, val sessionToken: String, val expiresAt: Instant)

class ChatAccessException(
    val code: String,
    val retryAfterSeconds: Long = 60L
) : RuntimeException(code)
