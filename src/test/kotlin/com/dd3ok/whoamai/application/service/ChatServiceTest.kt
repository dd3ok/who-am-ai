package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.service.career.CareerContextPlanner
import com.dd3ok.whoamai.application.service.career.CareerPromptAssembler
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatEvent
import com.dd3ok.whoamai.domain.ChatEventType
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.StreamMessage
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith

class ChatServiceTest {

    @Test
    fun `failed append reports incomplete events without generating another answer`() = runTest {
        var saves = 0
        var generations = 0
        val repository = object : ChatHistoryRepository {
            override suspend fun findByUserId(userId: String) = ChatHistory(userId)
            override suspend fun save(chatHistory: ChatHistory): ChatHistory {
                saves++
                throw IllegalStateException("append failed")
            }
        }
        val gemini = object : GeminiPort {
            override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
                generations++
                return flowOf("this answer")
            }
        }
        val service = createService(gemini, repository)
        val events = mutableListOf<ChatEvent>()
        assertFailsWith<IllegalStateException> {
            service.streamChatEvents(StreamMessage("shared", MessageType.USER, "this question")).toList(events)
        }
        assertEquals(listOf(ChatEventType.SOURCES, ChatEventType.DELTA), events.map { it.type })
        assertEquals(1, generations)
        assertEquals(1, saves)
    }

    @Test
    fun `resume intent without evidence cannot invoke a model or reuse an invented history claim`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val repository = InMemoryChatHistoryRepository()
        repository.seed("user-1", ChatHistory("user-1", listOf(
            ChatMessage("user", "유인재의 경력 알려줘"),
            ChatMessage("model", "존재하지 않는 대용량 시스템을 구축했습니다.")
        )))
        val service = createService(geminiPort = geminiPort, chatHistoryRepository = repository)

        val answer = service.streamChatResponse(
            StreamMessage(uuid = "user-1", type = MessageType.USER, content = "유인재의 백엔드 아키텍처 관련 강점이 뭐야?")
        ).toList().joinToString("")

        assertTrue(geminiPort.lastHistory.isEmpty(), "Missing evidence must not reach the model")
        assertTrue(answer.contains("확인 가능한 경력 자료"))
        assertTrue(!answer.contains("대용량 시스템"))
        assertEquals(answer, repository.findByUserId("user-1")!!.history.last().text)
    }

    @Test
    fun `saved chat history is trimmed before persistence`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val chatHistoryRepository = InMemoryChatHistoryRepository()
        val longMessage = "a".repeat(20000)
        val existingHistory = List(8) { index ->
            ChatMessage(
                role = if (index % 2 == 0) "user" else "model",
                text = "$index-$longMessage"
            )
        }
        chatHistoryRepository.seed("user-1", ChatHistory("user-1", existingHistory))

        val service = createService(
            geminiPort = geminiPort,
            chatHistoryRepository = chatHistoryRepository
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-1", type = MessageType.USER, content = "최근 대화 이어서 답해줘")
        ).toList()

        val savedHistory = chatHistoryRepository.findByUserId("user-1") ?: error("history not saved")
        assertTrue(savedHistory.history.size < existingHistory.size + 2)
        assertTrue(savedHistory.history.last().text == "응답")
    }

    @Test
    fun `declarative chat mentioning resume falls back to conversational prompt`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val service = createService(geminiPort = geminiPort)

        service.streamChatResponse(
            StreamMessage(uuid = "user-2", type = MessageType.USER, content = "어제 어떤 사람 이력서를 봤는데 별로였어")
        ).toList()

        val finalPrompt = geminiPort.lastHistory.last().text
        assertTrue(finalPrompt.startsWith("CHAT::"), "Expected conversational prompt fallback, but was: $finalPrompt")
    }

    @Test
    fun `own service implementation question should use rag prompt`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val service = createService(geminiPort = geminiPort)

        service.streamChatResponse(
            StreamMessage(uuid = "user-3", type = MessageType.USER, content = "이 서비스는 어떤 기술로 만들었나요")
        ).toList()

        val finalPrompt = geminiPort.lastHistory.last().text
        assertTrue(finalPrompt.startsWith("RAG::"), "Expected RAG prompt for own service, but was: $finalPrompt")
    }

    @Test
    fun `successful chat request records stage latency metrics`() = runTest {
        val meterRegistry = SimpleMeterRegistry()
        val service = createService(
            geminiPort = RecordingGeminiPort(),
            meterRegistry = meterRegistry
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-metrics", type = MessageType.USER, content = "안녕")
        ).toList()

        assertTimerRecorded(meterRegistry, "whoamai.chat.history.load.duration")
        assertTimerRecorded(meterRegistry, "whoamai.chat.history.save.duration")
        assertTimerRecorded(meterRegistry, "whoamai.chat.rag.rule.duration")
        assertTimerRecorded(meterRegistry, "whoamai.chat.gemini.first_token.duration")
        assertTimerRecorded(meterRegistry, "whoamai.chat.gemini.total.duration")
        assertTimerRecorded(meterRegistry, "whoamai.chat.first_token.duration")
        assertTimerRecorded(meterRegistry, "whoamai.chat.total.duration")
    }

    @Test
    fun `gemini stream timers start when response flow is collected`() = runTest {
        val clock = MockClock()
        val meterRegistry = SimpleMeterRegistry(SimpleConfig.DEFAULT, clock)
        val service = createService(
            geminiPort = RecordingGeminiPort(),
            meterRegistry = meterRegistry
        )

        val response = service.streamChatResponse(
            StreamMessage(uuid = "user-cold-flow", type = MessageType.USER, content = "안녕")
        )
        clock.add(Duration.ofSeconds(30))
        response.toList()

        assertEquals(
            0.0,
            meterRegistry.find("whoamai.chat.gemini.first_token.duration").timer()
                ?.totalTime(java.util.concurrent.TimeUnit.SECONDS) ?: -1.0,
            0.001
        )
        assertEquals(
            0.0,
            meterRegistry.find("whoamai.chat.gemini.total.duration").timer()
                ?.totalTime(java.util.concurrent.TimeUnit.SECONDS) ?: -1.0,
            0.001
        )
    }

    @Test
    fun `cancelled chat stream does not record error metric`() = runTest {
        val meterRegistry = SimpleMeterRegistry()
        val chatHistoryRepository = InMemoryChatHistoryRepository()
        val service = createService(
            geminiPort = RecordingGeminiPort(),
            chatHistoryRepository = chatHistoryRepository,
            meterRegistry = meterRegistry
        )

        val error = assertFailsWith<CancellationException> {
            service.streamChatResponse(
                StreamMessage(uuid = "user-cancel", type = MessageType.USER, content = "안녕")
            ).collect {
                throw CancellationException("client disconnected")
            }
        }

        assertEquals("client disconnected", error.message)
        assertEquals(0.0, meterRegistry.find("whoamai.chat.error.total").counter()?.count() ?: 0.0)
        assertEquals(null, chatHistoryRepository.savedHistory)
    }

    @Test
    fun `successful chat request appends to latest history loaded before save`() = runTest {
        val chatHistoryRepository = HistoryChangesBeforeSecondFindRepository()
        val service = createService(
            geminiPort = RecordingGeminiPort(),
            chatHistoryRepository = chatHistoryRepository
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-history", type = MessageType.USER, content = "안녕")
        ).toList()

        val savedTexts = chatHistoryRepository.savedHistory?.history?.map { it.text }.orEmpty()
        assertTrue(savedTexts.contains("다른 탭에서 먼저 저장된 응답"))
        assertEquals("응답", savedTexts.last())
    }

    @Test
    fun `successful chat request delegates completed exchange to append-only history repository`() = runTest {
        val chatHistoryRepository = AppendOnlyChatHistoryRepository()
        val service = createService(
            geminiPort = RecordingGeminiPort(),
            chatHistoryRepository = chatHistoryRepository
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-append", type = MessageType.USER, content = "hello")
        ).toList()

        assertEquals(1, chatHistoryRepository.findCount)
        assertEquals(
            listOf("hello", "응답"),
            chatHistoryRepository.appendedMessages.map { it.text }
        )
    }

    @Test
    fun `follow up question should use history enriched vector retrieval`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val chatHistoryRepository = InMemoryChatHistoryRepository()
        chatHistoryRepository.seed(
            "user-4",
            ChatHistory(
                "user-4",
                listOf(
                    ChatMessage(role = "user", text = "지마켓 경력 알려줘"),
                    ChatMessage(role = "model", text = "지마켓에서 백엔드 개발자로 근무했습니다.")
                )
            )
        )
        val persistencePort = HistoryAwareResumePersistencePort()
        val service = createService(
            geminiPort = geminiPort,
            chatHistoryRepository = chatHistoryRepository,
            resumeProviderPort = SampleResumeProviderPort(),
            resumePersistencePort = persistencePort
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-4", type = MessageType.USER, content = "거기서 사용한 기술은?")
        ).toList()

        val finalPrompt = geminiPort.lastHistory.last().text
        assertTrue(finalPrompt.startsWith("RAG::"), "Expected RAG prompt for history follow-up, but was: $finalPrompt")
        assertTrue(persistencePort.lastQuery?.contains("지마켓") == true)
    }

    @Test
    fun `long answer does not erase the user topic needed for the next follow up`() = runTest {
        val repository = InMemoryChatHistoryRepository()
        val persistencePort = HistoryAwareResumePersistencePort()
        var responseCount = 0
        val model = object : GeminiPort {
            override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> =
                flowOf(if (responseCount++ == 0) "a".repeat(40000) else "후속 응답")
        }
        val service = createService(model, repository, SampleResumeProviderPort(), persistencePort)

        service.streamChatEvents(StreamMessage("long-answer", MessageType.USER, "지마켓 경력 알려줘")).toList()
        val saved = repository.findByUserId("long-answer") ?: error("history not saved")
        assertTrue(saved.history.any { it.role == "user" && it.text == "지마켓 경력 알려줘" })

        val events = service.streamChatEvents(StreamMessage("long-answer", MessageType.USER, "그럼 사용한 기술은?")).toList()
        assertEquals(ChatEventType.DONE, events.last().type)
        assertTrue(persistencePort.lastQuery?.contains("지마켓") == true)
    }

    @Test
    fun `chat response continues when chat history storage is unavailable`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val service = createService(
            geminiPort = geminiPort,
            chatHistoryRepository = FailingChatHistoryRepository()
        )

        val chunks = service.streamChatResponse(
            StreamMessage(uuid = "user-history-down", type = MessageType.USER, content = "hello")
        ).toList()

        assertEquals(1, chunks.size)
        assertTrue(geminiPort.lastHistory.isNotEmpty())
    }

    private fun createService(
        geminiPort: GeminiPort,
        chatHistoryRepository: ChatHistoryRepository = InMemoryChatHistoryRepository(),
        resumeProviderPort: ResumeProviderPort = EmptyResumeProviderPort(),
        resumePersistencePort: ResumePersistencePort = EmptyResumePersistencePort(),
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry()
    ): ChatService {
        return ChatService(
            geminiPort = geminiPort,
            chatHistoryRepository = chatHistoryRepository,
            careerContextPlanner = CareerContextPlanner(
                llmRouter = LLMRouter(
                    resumeProviderPort = resumeProviderPort,
                    meterRegistry = meterRegistry
                ),
                contextRetriever = ContextRetriever(
                    resumePersistencePort = resumePersistencePort,
                    resumeProviderPort = resumeProviderPort,
                    ownDomainProfileProvider = OwnDomainProfileProvider(),
                    metadataFilterEnabled = true
                ),
                meterRegistry = meterRegistry,
                conversationTopicPort = ConversationTopicPort { _, _, _ -> null }
            ),
            careerPromptAssembler = CareerPromptAssembler(FakePromptProvider),
            meterRegistry = meterRegistry
        )
    }

    private class RecordingGeminiPort : GeminiPort {
        var lastHistory: List<ChatMessage> = emptyList()

        override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
            lastHistory = history
            return flowOf("응답")
        }
    }

    private class InMemoryChatHistoryRepository : ChatHistoryRepository {
        private val storage = mutableMapOf<String, ChatHistory>()
        var findCount: Int = 0
        var savedHistory: ChatHistory? = null

        fun seed(userId: String, history: ChatHistory) {
            storage[userId] = history
        }

        override suspend fun findByUserId(userId: String): ChatHistory? {
            findCount += 1
            return storage[userId]
        }

        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            savedHistory = chatHistory
            storage[chatHistory.userId] = chatHistory
            return chatHistory
        }
    }

    private class HistoryChangesBeforeSecondFindRepository : ChatHistoryRepository {
        var findCount: Int = 0
        var savedHistory: ChatHistory? = null

        override suspend fun findByUserId(userId: String): ChatHistory? {
            findCount += 1
            return if (findCount == 1) {
                ChatHistory(userId = userId)
            } else {
                ChatHistory(
                    userId = userId,
                    messages = listOf(ChatMessage(role = "model", text = "다른 탭에서 먼저 저장된 응답"))
                )
            }
        }

        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            savedHistory = chatHistory
            return chatHistory
        }
    }

    private class AppendOnlyChatHistoryRepository : ChatHistoryRepository {
        var findCount: Int = 0
        val appendedMessages = mutableListOf<ChatMessage>()

        override suspend fun findByUserId(userId: String): ChatHistory? {
            findCount += 1
            return ChatHistory(userId = userId)
        }

        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            throw AssertionError("ChatService should append completed exchanges instead of replacing chat history.")
        }

        override suspend fun appendMessages(userId: String, messages: List<ChatMessage>, maxTokens: Int): ChatHistory {
            appendedMessages += messages
            return ChatHistory(userId = userId, messages = appendedMessages)
        }
    }

    private class FailingChatHistoryRepository : ChatHistoryRepository {
        override suspend fun findByUserId(userId: String): ChatHistory? {
            throw IllegalStateException("history storage unavailable")
        }

        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            throw IllegalStateException("history storage unavailable")
        }
    }

    private class EmptyResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(name = "유인재")
        override fun isInitialized(): Boolean = true
    }

    private class SampleResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(
            name = "유인재",
            experiences = listOf(
                Experience(
                    company = "지마켓",
                    aliases = listOf("G마켓"),
                    period = Period("2021-10", "2025-01"),
                    position = "백엔드 개발자"
                )
            ),
            skills = listOf("Spring Boot")
        )

        override fun isInitialized(): Boolean = true
    }

    private class EmptyResumePersistencePort : ResumePersistencePort {
        override suspend fun index(resume: Resume): Int = 0
        override suspend fun findContentById(id: String): String? = null
        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> = emptyMap()
        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> =
            emptyList()
    }

    private class HistoryAwareResumePersistencePort : ResumePersistencePort {
        var lastQuery: String? = null

        override suspend fun index(resume: Resume): Int = 0
        override suspend fun findContentById(id: String): String? = null
        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> = emptyMap()

        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> {
            lastQuery = query
            return if (query.contains("지마켓")) {
                listOf(
                    ResumeSearchResult(
                        chunkId = "experience_지마켓",
                        chunkType = "experience",
                        content = "지마켓에서는 Spring Boot를 사용했습니다."
                    )
                )
            } else {
                emptyList()
            }
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
        ): String = "ROUTING::$question"

        override fun renderRagTemplate(context: String, question: String): String = "RAG::$question::$context"

        override fun renderConversationalTemplate(question: String): String = "CHAT::$question"
    }

    private fun assertTimerRecorded(meterRegistry: SimpleMeterRegistry, name: String) {
        assertEquals(1L, meterRegistry.find(name).timer()?.count() ?: 0L, "Expected $name to be recorded")
    }
}
