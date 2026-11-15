package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.service.career.CareerContextPlanner
import com.dd3ok.whoamai.application.service.career.CareerPromptAssembler
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.common.config.PromptProperties
import com.dd3ok.whoamai.common.service.PromptTemplateService
import com.dd3ok.whoamai.domain.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.DefaultResourceLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises actual routing, chunking, prompts, and source propagation. Does not grade a real model or vector index. */
class PortfolioRetrievalEvalTest {
    private val mapper = jacksonObjectMapper()
    private val resume: Resume = ClassPathResource("resume.json").inputStream.use { mapper.readValue(it) }
    private val cases: List<Case> = ClassPathResource("evals/portfolio-retrieval-cases.json").inputStream.use { mapper.readValue(it) }

    @TestFactory
    fun `portfolio retrieval contracts`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.id) {
            runTest {
                val registry = SimpleMeterRegistry()
                val model = RecordingModel()
                val history = MemoryHistory(case.history)
                val provider = object : ResumeProviderPort {
                    override fun getResume() = resume
                    override fun isInitialized() = true
                }
                val service = ChatService(
                    model, history, CareerContextPlanner(LLMRouter(provider, registry),
                    ContextRetriever(IndexedFixture(resume), provider, OwnDomainProfileProvider(), true), registry),
                    CareerPromptAssembler(PromptTemplateService(DefaultResourceLoader(), PromptProperties(
                        systemPath = "classpath:prompts/system.st",
                        routingInstructionPath = "classpath:prompts/routing-instruction.st",
                        routingTemplatePath = "classpath:prompts/routing-template.st",
                        ragTemplatePath = "classpath:prompts/rag-template.st",
                        conversationalTemplatePath = "classpath:prompts/conversational-template.st"
                    ))), registry
                )

                val events = service.streamChatEvents(StreamMessage("eval", MessageType.USER, case.question)).toList()

                assertEquals(setOf(case.mode), registry.find("whoamai.chat.request.total").counters()
                    .map { it.id.getTag("mode") }.toSet())
                assertEquals(case.sources.toSet(), events.first().sources.orEmpty().map { it.id }.toSet())
                case.sourceUrls.forEach { (id, url) ->
                    assertEquals(url, events.first().sources.orEmpty().single { it.id == id }.url)
                }
                assertEquals(ChatEventType.SOURCES, events.first().type)
                assertEquals(ChatEventType.DONE, events.last().type)
                val prompt = model.messages.last().text
                case.sources.forEach { assertTrue(prompt.contains(it), "Missing source in prompt: $it") }
                case.contextContains.forEach { assertTrue(prompt.contains(it), "Missing context: $it") }
                assertEquals(case.question, history.value.history.takeLast(2).first().text)
                assertEquals("테스트 응답", history.value.history.last().text)
            }
        }
    }

    private data class Case(
        val id: String,
        val question: String,
        val history: List<String> = emptyList(),
        val mode: String = "rag",
        val sources: List<String>,
        val contextContains: List<String> = emptyList(),
        val sourceUrls: Map<String, String> = emptyMap()
    )

    private class RecordingModel : GeminiPort {
        var messages: List<ChatMessage> = emptyList()
        override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
            messages = history
            return flowOf("테스트 응답")
        }
    }

    private class MemoryHistory(questions: List<String>) : ChatHistoryRepository {
        var value = ChatHistory("eval", questions.flatMap {
            listOf(ChatMessage("user", it), ChatMessage("model", "이전 답변"))
        })
        override suspend fun findByUserId(userId: String) = value
        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            value = chatHistory
            return value
        }
    }

    private class IndexedFixture(resume: Resume) : ResumePersistencePort {
        private val chunks = ResumeChunkingService(jacksonObjectMapper()).generateChunks(resume).associate { it.id to it.content }
        override suspend fun index(resume: Resume) = error("Evaluation must not reindex a database")
        override suspend fun findContentById(id: String) = chunks[id]
        override suspend fun findContentsByIds(ids: List<String>) = chunks.filterKeys { it in ids }
        // Semantic retrieval quality requires the separate, explicitly invoked live evaluation.
        override suspend fun searchSimilarSections(query: String, topK: Int, filter: Filter.Expression?, similarityThreshold: Double?) =
            emptyList<ResumeSearchResult>()
    }
}
