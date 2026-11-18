package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ChatAnswerCacheStore
import com.dd3ok.whoamai.common.config.ChatCacheProperties
import com.dd3ok.whoamai.domain.CachedChatAnswer
import com.dd3ok.whoamai.domain.ChatSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.Collections

@Repository
class MongoChatAnswerCacheAdapter(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val properties: ChatCacheProperties = ChatCacheProperties()
) : ChatAnswerCacheStore, MongoIndexWarmupTarget {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val indexMutex = Mutex()
    @Volatile private var indexesReady = false

    override suspend fun warmUp() = ensureIndexes()

    private val memoryCache: MutableMap<String, MemoryCacheItem> = Collections.synchronizedMap(
        object : LinkedHashMap<String, MemoryCacheItem>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoryCacheItem>): Boolean =
                size > properties.maxInMemoryEntries.coerceAtLeast(16)
        }
    )

    private suspend fun ensureIndexes() {
        if (indexesReady) return
        withTimeout(30_000) {
            indexMutex.withLock {
                if (!indexesReady) {
                    mongoTemplate.indexOps(ChatAnswerCacheDocument::class.java).ensureIndex(
                        Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO)
                    ).awaitSingle()
                    indexesReady = true
                }
            }
        }
    }

    override suspend fun find(cacheKey: String): CachedChatAnswer? {
        val now = Instant.now()
        val cachedInMemory = synchronized(memoryCache) { memoryCache[cacheKey] }
        if (cachedInMemory != null) {
            if (cachedInMemory.expiresAt.isAfter(now)) {
                return cachedInMemory.entry
            }
            memoryCache.remove(cacheKey)
        }

        return try {
            ensureIndexes()
            val document = mongoTemplate.findById(cacheKey, ChatAnswerCacheDocument::class.java)
                .awaitSingleOrNull()
                ?.takeIf { it.expiresAt.isAfter(now) }
                ?: return null

            val entry = document.toDomain()
            memoryCache[cacheKey] = MemoryCacheItem(entry, document.expiresAt)
            incrementHitCountSafely(cacheKey)
            entry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Chat answer cache lookup failed for key {}. Falling back to live RAG.", cacheKey, e)
            null
        }
    }

    override suspend fun save(entry: CachedChatAnswer, expiresAt: Instant) {
        if (!expiresAt.isAfter(Instant.now())) return
        memoryCache[entry.cacheKey] = MemoryCacheItem(entry, expiresAt)

        try {
            ensureIndexes()
            val document = ChatAnswerCacheDocument(
                cacheKey = entry.cacheKey,
                normalizedPrompt = entry.normalizedPrompt,
                answerText = entry.answerText,
                sources = entry.sources.map { CachedSourceRecord(it.id, it.title, it.url) },
                resumeVersion = entry.resumeVersion,
                hitCount = 0L,
                createdAt = Instant.now(),
                expiresAt = expiresAt
            )
            mongoTemplate.save(document).awaitSingle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Chat answer cache save failed for key {}. Continuing without persisted cache.", entry.cacheKey, e)
        }
    }

    override suspend fun deleteAll() {
        memoryCache.clear()
        try {
            ensureIndexes()
            mongoTemplate.remove(Query(), ChatAnswerCacheDocument::class.java).awaitSingleOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to clear chat answer cache documents.", e)
        }
    }

    private suspend fun incrementHitCountSafely(cacheKey: String) {
        try {
            mongoTemplate.updateFirst(
                Query(Criteria.where("_id").`is`(cacheKey)),
                Update().inc("hitCount", 1L),
                ChatAnswerCacheDocument::class.java
            ).awaitSingleOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Ignoring hitCount update failure for cache key {}.", cacheKey, e)
        }
    }

    private data class MemoryCacheItem(val entry: CachedChatAnswer, val expiresAt: Instant)
}

@Document("chat_answer_cache")
data class ChatAnswerCacheDocument(
    @Id val cacheKey: String,
    val normalizedPrompt: String = "",
    val answerText: String = "",
    val sources: List<CachedSourceRecord> = emptyList(),
    val resumeVersion: String = "",
    val hitCount: Long = 0L,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant = Instant.now()
) {
    fun toDomain(): CachedChatAnswer = CachedChatAnswer(
        cacheKey = cacheKey,
        normalizedPrompt = normalizedPrompt,
        answerText = answerText,
        sources = sources.map { ChatSource(it.id, it.title, it.url) },
        resumeVersion = resumeVersion
    )
}

data class CachedSourceRecord(
    val id: String = "",
    val title: String = "",
    val url: String? = null
)
