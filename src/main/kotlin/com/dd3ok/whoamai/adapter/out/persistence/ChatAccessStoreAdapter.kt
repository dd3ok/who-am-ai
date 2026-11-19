package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ChatAccessStore
import com.dd3ok.whoamai.domain.ChatSession
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

@Repository
class ChatAccessStoreAdapter(private val mongoTemplate: ReactiveMongoTemplate) : ChatAccessStore, MongoIndexWarmupTarget {
    private val indexMutex = Mutex()
    @Volatile private var indexesReady = false

    override suspend fun warmUp() = ensureIndexes()

    // Keep application liveness independent of MongoDB; chat access still requires successful index setup.
    private suspend fun ensureIndexes() {
        if (indexesReady) return
        withTimeout(30_000) {
            indexMutex.withLock {
                if (!indexesReady) {
                    for (type in listOf(StoredChatSession::class.java, ChatRequestBucket::class.java)) {
                        mongoTemplate.indexOps(type).createIndex(
                            Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO)
                        ).awaitSingle()
                    }
                    indexesReady = true
                }
            }
        }
    }

    override suspend fun createSession(tokenHash: String, session: ChatSession) {
        ensureIndexes()
        mongoTemplate.insert(StoredChatSession(tokenHash, session.conversationId, session.expiresAt)).awaitSingle()
    }

    override suspend fun findSession(tokenHash: String): ChatSession? {
        ensureIndexes()
        return mongoTemplate.findById(tokenHash, StoredChatSession::class.java).awaitSingleOrNull()
            ?.let { ChatSession(it.conversationId, it.expiresAt) }
    }

    override suspend fun incrementRequestCount(key: String, expiresAt: Instant): Long {
        ensureIndexes()
        val query = Query(Criteria.where("_id").`is`(key))
        val update = Update().inc("count", 1L).setOnInsert("expiresAt", expiresAt)
        return try {
            mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true),
                ChatRequestBucket::class.java).awaitSingle().count
        } catch (e: DuplicateKeyException) {
            // Two nodes can create the first bucket together. Retry the increment without inserting.
            mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
                ChatRequestBucket::class.java).awaitSingle().count
        }
    }

    override suspend fun findBlockExpiry(key: String): Instant? {
        ensureIndexes()
        return mongoTemplate.findById(key, ChatRequestBucket::class.java).awaitSingleOrNull()?.expiresAt
    }

    override suspend fun setBlockExpiry(key: String, expiresAt: Instant) {
        ensureIndexes()
        val query = Query(Criteria.where("_id").`is`(key))
        val update = Update().set("count", 1L).set("expiresAt", expiresAt)
        mongoTemplate.upsert(query, update, ChatRequestBucket::class.java).awaitSingle()
    }
}

@Document("chat_sessions")
data class StoredChatSession(@Id val tokenHash: String, val conversationId: String, val expiresAt: Instant)

@Document("chat_request_buckets")
data class ChatRequestBucket(@Id val key: String, val count: Long, val expiresAt: Instant)
