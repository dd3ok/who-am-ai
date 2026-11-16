package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.CachedChatAnswer
import java.time.Instant

interface ChatAnswerCacheStore {
    suspend fun find(cacheKey: String): CachedChatAnswer?
    suspend fun save(entry: CachedChatAnswer, expiresAt: Instant)
    suspend fun deleteAll() {}
}
