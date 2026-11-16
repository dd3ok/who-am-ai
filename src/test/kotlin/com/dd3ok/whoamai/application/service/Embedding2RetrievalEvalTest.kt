package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.domain.ChatEventType
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.StreamMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore
import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.data.mongodb.core.MongoTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in real Gemini + Atlas check. Uses and removes a dedicated disposable evaluation database. */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = [
    "spring.mongodb.uri=\${EVAL_MONGO_URI}",
    "logging.level.org.mongodb.driver=OFF"
])
class Embedding2RetrievalEvalTest {
    @Autowired private lateinit var mongo: MongoTemplate
    @Autowired private lateinit var properties: MongoDBAtlasVectorStoreProperties
    @Autowired private lateinit var vectorStore: MongoDBAtlasVectorStore
    @Autowired private lateinit var manageResume: ManageResumeUseCase
    @Autowired private lateinit var resumeProvider: ResumeProviderPort
    @Autowired private lateinit var chunking: ResumeChunkingService
    @Autowired private lateinit var chat: ChatUseCase

    private val mapper = jacksonObjectMapper()
    private var disposableDatabase = false

    @BeforeAll
    fun prepare() = runBlocking {
        require(Regex("whoamai_embedding2_eval_[a-z0-9_]+").matches(mongo.db.name)) {
            "Use a disposable EVAL_MONGO_URI database named whoamai_embedding2_eval_<unique suffix>."
        }
        require(mongo.db.listCollectionNames().first() == null) { "Evaluation database must be new and empty." }
        disposableDatabase = true
        val chunks = chunking.generateChunks(resumeProvider.getResume())
        assertEquals("Resume indexing process finished. Indexed ${chunks.size} documents.", manageResume.reindexResumeData())
        val collection = mongo.getCollection(properties.collectionName)
        val stored = collection.find().toList()
        assertEquals(chunks.size, stored.size)
        val expected = chunks.associateBy { it.id }
        stored.forEach { document ->
            val metadata = document.get("metadata", Document::class.java)
            assertEquals(expected.getValue(metadata.getString("chunk_id")).content, document.getString("content"))
            val vector = document.getList(properties.pathName, Number::class.java).map { it.toDouble() }
            assertEquals(768, vector.size)
            assertTrue(vector.all { it.isFinite() })
            assertTrue(sqrt(vector.sumOf { it * it }) in 0.99..1.01)
        }
        val index = ClassPathResource("atlas-index.json").inputStream.bufferedReader().use { Document.parse(it.readText()) }
        mongo.db.runCommand(Document("createSearchIndexes", properties.collectionName).append("indexes", listOf(
            Document("name", properties.indexName).append("type", "vectorSearch").append("definition", index)
        )))
        val vector = stored.first()[properties.pathName]
        val deadline = System.nanoTime() + 120_000_000_000L
        var ready = false
        while (System.nanoTime() < deadline) {
            val status = collection.aggregate(listOf(Document("\$listSearchIndexes", Document("name", properties.indexName)))).first()
            if (status?.getBoolean("queryable") == true) {
                val count = collection.aggregate(listOf(Document("\$vectorSearch", Document(mapOf(
                    "index" to properties.indexName, "path" to properties.pathName, "queryVector" to vector,
                    "numCandidates" to 200, "limit" to chunks.size
                ))))).toList().size
                if (count == chunks.size) { ready = true; break }
            }
            Thread.sleep(2000)
        }
        assertTrue(ready, "Atlas vector index did not become ready with every chunk within 120 seconds.")
        println("EMBEDDING2_EVAL|indexed=${stored.size}|dimensions=768|normalized=true|index=ready")
    }

    @Test
    fun `real Korean queries retrieve their expected source in the first three results`() {
        val cases: List<Case> = ClassPathResource("evals/embedding2-retrieval-cases.json").inputStream.use { mapper.readValue(it) }
        val report = cases.map { case ->
            val results = vectorStore.similaritySearch(SearchRequest.builder().query(case.question).topK(3).similarityThreshold(0.0).build())
            val rank = results.indexOfFirst { it.metadata["chunk_id"] == case.expectedChunk }
            mapOf("question" to case.question, "expectedChunk" to case.expectedChunk,
                "rank" to if (rank < 0) null else rank + 1,
                "results" to results.map { mapOf("chunkId" to it.metadata["chunk_id"], "score" to it.score) })
        }
        val hits = report.count { it["rank"] != null }
        val output = Path.of("build/reports/embedding2-retrieval-eval.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf("model" to "gemini-embedding-2", "dimensions" to 768, "cases" to cases.size, "hitAt3" to hits, "results" to report)
        ))
        println("EMBEDDING2_EVAL|queries=${cases.size}|hit_at_3=$hits")
        assertEquals(cases.size, hits, "See build/reports/embedding2-retrieval-eval.json for missing expected sources.")
    }

    @Test
    fun `real chat keeps project sources through a short follow up`() = runBlocking {
        val session = "embedding2-eval-${System.currentTimeMillis()}"
        for (question in listOf("ESM 로그인 개편을 설명해줘", "그럼 사용한 기술은?")) {
            val events = withTimeout(120_000) {
                chat.streamChatEvents(StreamMessage(session, MessageType.USER, question)).toList()
            }
            assertTrue(events.first().sources.orEmpty().any { it.id == "project_ESM_로그인_개편" })
            assertEquals(ChatEventType.DONE, events.last().type)
            val answer = events.filter { it.type == ChatEventType.DELTA }.joinToString("") { it.content.orEmpty() }
            assertTrue(answer.contains("Spring Boot", ignoreCase = true), answer)
            println("EMBEDDING2_CHAT|question=$question|answer=${answer.replace('\n', ' ')}")
        }
    }

    @AfterAll
    fun cleanup() {
        if (disposableDatabase) mongo.db.drop()
    }

    private data class Case(val question: String, val expectedChunk: String)
}
