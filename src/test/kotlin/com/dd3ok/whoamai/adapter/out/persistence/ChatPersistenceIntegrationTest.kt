package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.ChatSession
import com.mongodb.ConnectionString
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactory
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("mongo")
class ChatPersistenceIntegrationTest {
    private lateinit var client: MongoClient
    private lateinit var template: ReactiveMongoTemplate
    private lateinit var history: ChatHistoryRepositoryAdapter
    private lateinit var access: ChatAccessStoreAdapter

    @BeforeEach
    fun connect() {
        val uri = System.getenv("TEST_MONGO_URI") ?: error("TEST_MONGO_URI is required")
        val connection = ConnectionString(uri)
        require(connection.hosts.all { it.substringBefore(':') in setOf("localhost", "127.0.0.1") }) {
            "Mongo integration tests only connect to a local test server"
        }
        client = MongoClients.create(uri)
        // Always create an isolated database; never clear a database supplied by the user.
        template = ReactiveMongoTemplate(client, "whoamai_test_" + UUID.randomUUID().toString().replace("-", ""))
        history = ChatHistoryRepositoryAdapter(
            ReactiveMongoRepositoryFactory(template).getRepository(ChatHistoryDocumentRepository::class.java), template, uri
        )
        access = ChatAccessStoreAdapter(template)
    }

    @AfterEach
    fun disconnect() = runBlocking {
        if (::template.isInitialized) template.mongoDatabase.awaitSingle().drop().let { reactor.core.publisher.Mono.from(it).awaitSingleOrNull() }
        if (::client.isInitialized) client.close()
    }

    @Test
    fun `concurrent appends retain every successfully committed exchange`() = runBlocking {
        history.appendMessages("shared", listOf(ChatMessage("user", "initial")), 8192)
        val results = (1..12).map { i -> async {
            i to runCatching { history.appendMessages("shared", listOf(ChatMessage("model", "answer-$i")), 8192) }
        } }.awaitAll()
        val committed = results.filter { it.second.isSuccess }.map { "answer-${it.first}" }
        assertTrue(committed.isNotEmpty())
        val saved = assertNotNull(history.findByUserId("shared")).history.map { it.text }
        assertEquals(setOf("initial") + committed, saved.toSet())
        assertEquals(1 + committed.size, saved.size)
        assertEquals((1 + committed.size).toLong(), template.findById("shared", ChatHistoryDocument::class.java).awaitSingle().revision)
    }

    @Test
    fun `legacy documents gain a revision without losing history`() = runBlocking<Unit> {
        template.insert(Document("_id", "legacy").append("messages", listOf(Document("role", "user").append("text", "old"))), "chat_histories").awaitSingle()
        val saved = history.appendMessages("legacy", listOf(ChatMessage("model", "new")), 8192)
        assertEquals(listOf("old", "new"), saved.history.map { it.text })
        assertEquals(1L, template.findById("legacy", ChatHistoryDocument::class.java).awaitSingle().revision)
    }

    @Test
    fun `distributed counters count concurrent first inserts exactly and have expiry indexes`() = runBlocking {
        val otherNode = ChatAccessStoreAdapter(template)
        val counts = (1..40).map { i -> async {
            (if (i % 2 == 0) access else otherNode).incrementRequestCount("shared-bucket", Instant.now().plusSeconds(120))
        } }.awaitAll()
        assertEquals((1L..40L).toSet(), counts.toSet())
        for (type in listOf(StoredChatSession::class.java, ChatRequestBucket::class.java)) {
            assertTrue(template.indexOps(type).indexInfo.collectList().awaitSingle().any { it.expireAfter.isPresent && it.expireAfter.get().seconds == 0L })
        }
    }

    @Test
    fun `session survives another adapter instance`() = runBlocking {
        val session = ChatSession("conversation", Instant.ofEpochSecond(1900000000))
        access.createSession("hashed-token", session)
        assertEquals(session, ChatAccessStoreAdapter(template).findSession("hashed-token"))
        assertEquals(null, access.findSession("unknown-token"))
    }
}
