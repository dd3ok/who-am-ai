package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.port.out.ChatAnswerCacheStore
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.service.ChatService
import com.dd3ok.whoamai.application.service.ContextRetriever
import com.dd3ok.whoamai.application.service.LLMRouter
import com.dd3ok.whoamai.application.service.OwnDomainProfileProvider
import com.dd3ok.whoamai.common.config.ChatCacheProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.CachedChatAnswer
import com.dd3ok.whoamai.domain.ChatEvent
import com.dd3ok.whoamai.domain.ChatEventType
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.ChatSource
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Project
import com.dd3ok.whoamai.domain.PromptCacheKey
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.StreamMessage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import java.time.Instant

class ChatAnswerCacheTest {

    @Test
    fun `prompt normalization handles spacing, casing, and trailing punctuation`() {
        assertEquals(
            "who-am-ai는 어떤 구조로 만들었어",
            PromptCacheKey.normalize("  WHO-AM-AI는   어떤 구조로   만들었어?! ")
        )
        val keyA = PromptCacheKey.build(PromptCacheKey.normalize("지마켓 슈퍼딜 알려줘?"), "v1")
        val keyB = PromptCacheKey.build(PromptCacheKey.normalize("  지마켓 슈퍼딜 알려줘  "), "v1")
        val keyV2 = PromptCacheKey.build(PromptCacheKey.normalize("지마켓 슈퍼딜 알려줘?"), "v2")
        assertEquals(keyA, keyB)
        assertNotEquals(keyA, keyV2)
    }

    @Test
    fun `pacer streams SOURCES, realistic initial delay, chunked DELTAs preserving exact text`() = runTest {
        val recordedDelays = mutableListOf<Long>()
        val properties = ChatCacheProperties(
            initialDelayMinMs = 450,
            initialDelayMaxMs = 450,
            targetStreamDurationMs = 1600,
            chunkDelayMinMs = 20,
            chunkDelayMaxMs = 20,
            pauseDelayMs = 60
        )
        val pacer = CachedAnswerStreamPacer(
            properties = properties,
            sleep = { recordedDelays += it },
            randomLong = { min, _ -> min }
        )
        val answer = """
            지마켓에서 백엔드 개발을 담당했습니다.
            - ESM 셀러딜 신청 프로세스를 설계하고 개발했습니다.
            - 비동기 검증 분리로 API 응답 시간을 개선했습니다.
        """.trimIndent()
        val entry = CachedChatAnswer(
            cacheKey = "k1",
            normalizedPrompt = "지마켓 작업",
            answerText = answer,
            sources = listOf(ChatSource("exp_gmarket", "지마켓"))
        )

        val events = pacer.streamEvents(entry).toList()

        assertEquals(ChatEventType.SOURCES, events.first().type)
        val deltas = events.filter { it.type == ChatEventType.DELTA }.mapNotNull { it.content }
        assertTrue(deltas.size >= 5, "Expected answer to be split across smooth multiple chunks")
        assertEquals(answer, deltas.joinToString(""))
        assertEquals(450L, recordedDelays.first())
        assertTrue(recordedDelays.drop(1).any { it == 60L }, "Expected sentence/newline pause delay")
    }

    @Test
    fun `repeated starter and standalone follow-up questions hit cache without calling gemini twice while keeping history`() = runTest {
        val cacheStore = InMemoryCacheStore()
        val historyRepo = InMemoryHistoryRepo()
        val meterRegistry = SimpleMeterRegistry()
        var geminiCalls = 0
        val gemini = object : GeminiPort {
            override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
                geminiCalls += 1
                val question = history.last().text
                return flowOf("생성 응답($geminiCalls): ", question.take(20))
            }
        }
        val service = createCachedChatService(
            geminiPort = gemini,
            historyRepo = historyRepo,
            cacheStore = cacheStore,
            meterRegistry = meterRegistry
        )

        val firstEvents = service.streamChatEvents(
            StreamMessage("session-a", MessageType.USER, "who-am-ai는 어떤 구조로 만들었어?")
        ).toList()
        assertEquals(1, geminiCalls)
        assertEquals(ChatEventType.DONE, firstEvents.last().type)

        // Second user asks equivalent question -> cache hit, 0 extra Gemini calls, full text & history saved.
        val secondEvents = service.streamChatEvents(
            StreamMessage("session-b", MessageType.USER, "WHO-AM-AI는  어떤 구조로 만들었어 ")
        ).toList()
        assertEquals(1, geminiCalls)
        assertEquals(ChatEventType.DONE, secondEvents.last().type)
        val streamedSecondText = secondEvents.filter { it.type == ChatEventType.DELTA }.joinToString("") { it.content.orEmpty() }
        assertTrue(streamedSecondText.startsWith("생성 응답(1):"))
        assertEquals(2, historyRepo.findByUserId("session-b")!!.history.size)

        // Follow-up explicitly naming a standalone project still hits & populates cache mid-conversation.
        service.streamChatEvents(
            StreamMessage("session-b", MessageType.USER, "지마켓 슈퍼딜 신청 프로젝트에서 해결한 문제와 기여를 설명해 주세요")
        ).toList()
        assertEquals(2, geminiCalls)

        service.streamChatEvents(
            StreamMessage("session-c", MessageType.USER, "지마켓 슈퍼딜 신청 프로젝트에서 해결한 문제와 기여를 설명해 주세요")
        ).toList()
        assertEquals(2, geminiCalls)

        // Contextual deictic or contrast follow-ups must bypass cache and invoke Gemini with session history.
        service.streamChatEvents(
            StreamMessage("session-b", MessageType.USER, "거기서 제일 어려웠던 점은?")
        ).toList()
        assertEquals(3, geminiCalls)

        service.streamChatEvents(
            StreamMessage("session-b", MessageType.USER, "지마켓 말고 다른 경력도 알려줘")
        ).toList()
        assertEquals(4, geminiCalls)

        // Short greetings must bypass cache lookups cleanly.
        service.streamChatEvents(
            StreamMessage("session-d", MessageType.USER, "안녕하세요")
        ).toList()
        assertEquals(5, geminiCalls)

        assertEquals(2.0, meterRegistry.counter("whoamai.chat.cache.total", "result", "hit").count())
        assertEquals(2.0, meterRegistry.counter("whoamai.chat.cache.total", "result", "miss").count())
        assertEquals(3.0, meterRegistry.counter("whoamai.chat.cache.total", "result", "bypass").count())
    }

    @Test
    fun `resume update invalidates cache keys automatically and missing evidence is never cached`() = runTest {
        val cacheStore = InMemoryCacheStore()
        val resumeProvider = MutableResumeProvider(
            Resume(
                name = "유인재",
                experiences = listOf(Experience(company = "지마켓", period = Period("2021-10", "2025-01"), position = "Backend")),
                projects = listOf(Project(title = "ESM+", company = "지마켓", period = Period("2023-09", "2024-01")))
            )
        )
        var geminiCalls = 0
        val gemini = object : GeminiPort {
            override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
                geminiCalls += 1
                return flowOf("답변-$geminiCalls")
            }
        }
        val service = createCachedChatService(
            geminiPort = gemini,
            cacheStore = cacheStore,
            resumeProviderPort = resumeProvider
        )

        service.streamChatEvents(StreamMessage("s1", MessageType.USER, "지마켓 ESM+ 설명해줘")).toList()
        service.streamChatEvents(StreamMessage("s2", MessageType.USER, "지마켓 ESM+ 설명해줘")).toList()
        assertEquals(1, geminiCalls)

        // Missing-evidence response must not invoke Gemini or pollute cache.
        val missing = service.streamChatEvents(
            StreamMessage("s3", MessageType.USER, "유인재의 존재하지 않는 비공개 프로젝트 알려줘")
        ).toList().filter { it.type == ChatEventType.DELTA }.joinToString("") { it.content.orEmpty() }
        assertTrue(missing.contains("확인 가능한 경력 자료"))
        assertEquals(1, geminiCalls)
        assertEquals(1, cacheStore.entries.size)

        // Updating resume changes version hash and forces a fresh answer on the next request.
        resumeProvider.current = resumeProvider.current.copy(summary = "업데이트된 경력 요약")
        val updated = service.streamChatEvents(StreamMessage("s4", MessageType.USER, "지마켓 ESM+ 설명해줘"))
            .toList().filter { it.type == ChatEventType.DELTA }.joinToString("") { it.content.orEmpty() }
        assertEquals("답변-2", updated)
        assertEquals(2, geminiCalls)
    }

    private fun createCachedChatService(
        geminiPort: GeminiPort,
        cacheStore: ChatAnswerCacheStore,
        historyRepo: ChatHistoryRepository = InMemoryHistoryRepo(),
        resumeProviderPort: ResumeProviderPort = SampleResumeProvider(),
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry()
    ): ChatService {
        val llmRouter = LLMRouter(resumeProviderPort, meterRegistry)
        val ownDomainProfileProvider = OwnDomainProfileProvider()
        val persistencePort = SamplePersistencePort()
        val contextRetriever = ContextRetriever(persistencePort, resumeProviderPort, ownDomainProfileProvider, true)
        val planner = CareerContextPlanner(llmRouter, contextRetriever, meterRegistry, { _, _, _ -> null })
        val assembler = CareerPromptAssembler(FakePromptProvider)
        val coordinator = ChatAnswerCacheCoordinator(
            cacheStore = cacheStore,
            resumeProviderPort = resumeProviderPort,
            ownDomainProfileProvider = ownDomainProfileProvider,
            llmRouter = llmRouter,
            meterRegistry = meterRegistry,
            properties = ChatCacheProperties(enabled = true)
        )
        val pacer = CachedAnswerStreamPacer(
            properties = ChatCacheProperties(
                initialDelayMinMs = 0,
                initialDelayMaxMs = 0,
                chunkDelayMinMs = 0,
                chunkDelayMaxMs = 0,
                pauseDelayMs = 0
            ),
            sleep = {},
            randomLong = { min, _ -> min }
        )
        return ChatService(geminiPort, historyRepo, planner, assembler, meterRegistry, coordinator, pacer)
    }

    private class InMemoryCacheStore : ChatAnswerCacheStore {
        val entries = mutableMapOf<String, CachedChatAnswer>()

        override suspend fun find(cacheKey: String): CachedChatAnswer? = entries[cacheKey]

        override suspend fun save(entry: CachedChatAnswer, expiresAt: Instant) {
            entries[entry.cacheKey] = entry
        }

        override suspend fun deleteAll() {
            entries.clear()
        }
    }

    private class InMemoryHistoryRepo : ChatHistoryRepository {
        private val map = mutableMapOf<String, ChatHistory>()
        override suspend fun findByUserId(userId: String): ChatHistory? = map[userId]
        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            map[chatHistory.userId] = chatHistory
            return chatHistory
        }
    }

    private class MutableResumeProvider(var current: Resume) : ResumeProviderPort {
        override fun getResume(): Resume = current
        override fun isInitialized(): Boolean = true
    }

    private class SampleResumeProvider : ResumeProviderPort {
        private val resume = Resume(
            name = "유인재",
            experiences = listOf(Experience(company = "지마켓", period = Period("2021-10", "2025-01"), position = "백엔드 개발자")),
            projects = listOf(Project(title = "지마켓 슈퍼딜 신청", company = "지마켓", period = Period("2023-09", "2024-01")))
        )
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    private class SamplePersistencePort : ResumePersistencePort {
        override suspend fun index(resume: Resume): Int = 0
        override suspend fun findContentById(id: String): String? = null
        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> = emptyMap()
        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> =
            if (query.contains("지마켓")) {
                listOf(
                    ResumeSearchResult(
                        chunkId = "experience_지마켓",
                        chunkType = "experience",
                        content = "지마켓 슈퍼딜 신청 및 ESM+ 인증 시스템 백엔드를 개발했습니다."
                    )
                )
            } else {
                emptyList()
            }
    }

    private object FakePromptProvider : PromptProvider {
        override fun systemInstruction(): String = ""
        override fun routingInstruction(): String = ""
        override fun renderRoutingTemplate(
            resumeOwnerName: String,
            companies: List<String>,
            skills: List<String>,
            question: String
        ): String = question
        override fun renderRagTemplate(context: String, question: String): String = "RAG::$question::$context"
        override fun renderConversationalTemplate(question: String): String = "CHAT::$question"
    }
}
