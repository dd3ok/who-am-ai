package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.domain.ChatEventType
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.StreamMessage
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.eval.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.mockito.Mockito
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore
import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(properties = [
    "spring.mongodb.uri=\${EVAL_MONGO_URI}",
    "logging.level.org.mongodb.driver=OFF"
])
class CareerAgentLiveEvalTest {
    @Autowired private lateinit var mongo: MongoTemplate
    @Autowired private lateinit var properties: MongoDBAtlasVectorStoreProperties
    @Autowired private lateinit var vectorStore: MongoDBAtlasVectorStore
    @Autowired private lateinit var manageResume: ManageResumeUseCase
    @Autowired private lateinit var resumeProvider: ResumeProviderPort
    @Autowired private lateinit var chunking: ResumeChunkingService
    @Autowired private lateinit var chat: ChatUseCase
    @Autowired private lateinit var chatProperties: GeminiChatModelProperties
    @Autowired private lateinit var serviceProfile: OwnDomainProfileProvider
    @Autowired private lateinit var meters: MeterRegistry
    @MockitoSpyBean private lateinit var streamingModel: StreamingChatModel

    private val mapper = jacksonObjectMapper()
    private var disposableDatabase = false
    private lateinit var references: List<EvalSource>
    private lateinit var judge: GeminiAnswerJudge
    private var judgeCalibrated = false
    private val calibrationResults = mutableListOf<Map<String, Any?>>()
    private val answerResults = mutableListOf<Map<String, Any?>>()

    @BeforeAll
    fun prepare() = runBlocking {
        require(Regex("whoamai_embedding2_eval_[a-z0-9_]+").matches(mongo.db.name)) {
            "Use a disposable EVAL_MONGO_URI database named whoamai_embedding2_eval_<unique suffix>."
        }
        require(mongo.db.listCollectionNames().first() == null) { "Evaluation database must be new and empty." }
        disposableDatabase = true
        val chunks = chunking.generateChunks(resumeProvider.getResume())
        references = chunks.map { EvalSource(it.id, it.content) } + EvalSource("service_profile", serviceProfile.serviceProfile())
        judge = GeminiAnswerJudge(streamingModel, chatProperties)
        writeJudgeReport()
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
    @Order(1)
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
    @Order(2)
    fun `real judge distinguishes grounded answers errors and insufficient evidence`() = runBlocking {
        val cases: List<CalibrationCase> = ClassPathResource("evals/answer-judge-calibration.json").inputStream.use { mapper.readValue(it) }
        for (case in cases) {
            val selected = case.referenceIds.map { id -> references.single { it.id == id } }
            val sample = AnswerSample(case.question, case.history, selected, selected.joinToString("\n") { it.text }, case.answer)
            evaluateAndRecord(case.id, sample, calibrationResults, case.expected)
        }
        assertTrue(calibrationResults.all { it["matchesExpected"] == true }, "Judge calibration failed; inspect build/reports/answer-quality-eval.json.")
        judgeCalibrated = true
    }

    @Test
    @Order(3)
    fun `real answers are grounded relevant and preserve follow up context`() = runBlocking {
        assertTrue(judgeCalibrated, "Calibrate the judge successfully before trusting its answer evaluation.")
        val cases: List<ConversationCase> = ClassPathResource("evals/answer-quality-cases.json").inputStream.use { mapper.readValue(it) }
        for (case in cases) {
            val session = "embedding2-eval-${case.id}-${System.nanoTime()}"
            val history = mutableListOf<ChatMessage>()
            for ((index, turn) in case.turns.withIndex()) {
                Mockito.clearInvocations(streamingModel)
                val vectorCallsBefore = meters.find("whoamai.chat.rag.vector.duration").timer()?.count() ?: 0
                val events = withTimeout(120_000) {
                    chat.streamChatEvents(StreamMessage(session, MessageType.USER, turn.question)).toList()
                }
                val sourceIds = events.firstOrNull()?.sources.orEmpty().map { it.id }
                val answer = events.filter { it.type == ChatEventType.DELTA }.joinToString("") { it.content.orEmpty() }
                val checks = linkedMapOf(
                    "sourcesFirst" to (events.firstOrNull()?.type == ChatEventType.SOURCES),
                    "expectedSourcesPresent" to sourceIds.containsAll(turn.expectedSources),
                    "doneLast" to (events.lastOrNull()?.type == ChatEventType.DONE),
                    "nonBlankAnswer" to answer.isNotBlank()
                )
                if (turn.requireVectorSearch) {
                    checks["vectorSearchUsed"] =
                        meters.find("whoamai.chat.rag.vector.duration").timer()?.count() == vectorCallsBefore + 1
                }
                // Capture the exact RAG prompt sent to Gemini, rather than reconstructing context from source IDs.
                val prompt = Mockito.mockingDetails(streamingModel).invocations
                    .lastOrNull { it.method.name == "stream" && it.arguments.firstOrNull() is Prompt }
                    ?.arguments?.first() as? Prompt
                val context = prompt?.instructions?.lastOrNull()?.text.orEmpty()
                val sample = AnswerSample(turn.question, history.toList(), references, context, answer)
                evaluateAndRecord("${case.id}-$index", sample, answerResults, Verdict.PASS, checks,
                    mapOf("expectedSources" to turn.expectedSources, "actualSources" to sourceIds))
                history += ChatMessage("user", turn.question)
                history += ChatMessage("model", answer)
            }
        }
        assertTrue(answerResults.all { it["matchesExpected"] == true }, "Answer quality failed or needs review; inspect build/reports/answer-quality-eval.json.")
    }

    private suspend fun evaluateAndRecord(
        id: String, sample: AnswerSample, results: MutableList<Map<String, Any?>>, expected: Verdict,
        objectiveChecks: Map<String, Boolean> = emptyMap(), observations: Map<String, Any?> = emptyMap()
    ) {
        val row = mutableMapOf<String, Any?>("id" to id, "sample" to sample, "expected" to expected,
            "objectiveChecks" to objectiveChecks, "observations" to observations, "matchesExpected" to false)
        results += row
        try {
            val result = judge.evaluate(sample)
            row["judgement"] = result
            row["matchesExpected"] = result.verdict == expected && objectiveChecks.values.all { it }
            println("ANSWER_EVAL|id=$id|expected=$expected|actual=${result.verdict}|checks=$objectiveChecks")
        } catch (e: Exception) {
            row["error"] = e::class.java.simpleName
            throw e
        } finally {
            writeJudgeReport()
        }
    }

    private fun writeJudgeReport() {
        val output = Path.of("build/reports/answer-quality-eval.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
            "judgeModel" to judge.modelName, "calibration" to calibrationResults, "answers" to answerResults
        )))
    }

    @AfterAll
    fun cleanup() {
        if (disposableDatabase) mongo.db.drop()
    }

    private data class Case(val question: String, val expectedChunk: String)
    private data class CalibrationCase(val id: String, val question: String, val history: List<ChatMessage> = emptyList(),
        val referenceIds: List<String>, val answer: String, val expected: Verdict)
    private data class ConversationCase(val id: String, val turns: List<Turn>)
    private data class Turn(val question: String, val expectedSources: List<String>, val requireVectorSearch: Boolean = false)
}
