package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.StreamMessage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter

class ChatServiceTest {

    @Test
    fun `resume intent with empty context falls back to conversational prompt`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val service = ChatService(
            geminiPort = geminiPort,
            chatHistoryRepository = InMemoryChatHistoryRepository(),
            llmRouter = LLMRouter(
                resumeProviderPort = EmptyResumeProviderPort(),
                meterRegistry = SimpleMeterRegistry()
            ),
            contextRetriever = ContextRetriever(
                resumePersistencePort = EmptyResumePersistencePort(),
                resumeProviderPort = EmptyResumeProviderPort(),
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            promptTemplateService = FakePromptProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-1", type = MessageType.USER, content = "백엔드 아키텍처 관련 강점이 뭐야?")
        ).toList()

        val finalPrompt = geminiPort.lastHistory.last().text
        assertTrue(finalPrompt.startsWith("CHAT::"), "Expected conversational prompt fallback, but was: $finalPrompt")
    }

    @Test
    fun `saved chat history is trimmed before persistence`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val chatHistoryRepository = InMemoryChatHistoryRepository()
        val longMessage = "a".repeat(2000)
        val existingHistory = List(8) { index ->
            ChatMessage(
                role = if (index % 2 == 0) "user" else "model",
                text = "$index-$longMessage"
            )
        }
        chatHistoryRepository.seed("user-1", ChatHistory("user-1", existingHistory))

        val service = ChatService(
            geminiPort = geminiPort,
            chatHistoryRepository = chatHistoryRepository,
            llmRouter = LLMRouter(
                resumeProviderPort = EmptyResumeProviderPort(),
                meterRegistry = SimpleMeterRegistry()
            ),
            contextRetriever = ContextRetriever(
                resumePersistencePort = EmptyResumePersistencePort(),
                resumeProviderPort = EmptyResumeProviderPort(),
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            promptTemplateService = FakePromptProvider,
            meterRegistry = SimpleMeterRegistry()
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
        val service = ChatService(
            geminiPort = geminiPort,
            chatHistoryRepository = InMemoryChatHistoryRepository(),
            llmRouter = LLMRouter(
                resumeProviderPort = EmptyResumeProviderPort(),
                meterRegistry = SimpleMeterRegistry()
            ),
            contextRetriever = ContextRetriever(
                resumePersistencePort = EmptyResumePersistencePort(),
                resumeProviderPort = EmptyResumeProviderPort(),
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            promptTemplateService = FakePromptProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-2", type = MessageType.USER, content = "어제 어떤 사람 이력서를 봤는데 별로였어")
        ).toList()

        val finalPrompt = geminiPort.lastHistory.last().text
        assertTrue(finalPrompt.startsWith("CHAT::"), "Expected conversational prompt fallback, but was: $finalPrompt")
    }

    @Test
    fun `own service implementation question should use rag prompt`() = runTest {
        val geminiPort = RecordingGeminiPort()
        val service = ChatService(
            geminiPort = geminiPort,
            chatHistoryRepository = InMemoryChatHistoryRepository(),
            llmRouter = LLMRouter(
                resumeProviderPort = EmptyResumeProviderPort(),
                meterRegistry = SimpleMeterRegistry()
            ),
            contextRetriever = ContextRetriever(
                resumePersistencePort = EmptyResumePersistencePort(),
                resumeProviderPort = EmptyResumeProviderPort(),
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            promptTemplateService = FakePromptProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-3", type = MessageType.USER, content = "이 서비스는 어떤 기술로 만들었나요")
        ).toList()

        val finalPrompt = geminiPort.lastHistory.last().text
        assertTrue(finalPrompt.startsWith("RAG::"), "Expected RAG prompt for own service, but was: $finalPrompt")
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
        val service = ChatService(
            geminiPort = geminiPort,
            chatHistoryRepository = chatHistoryRepository,
            llmRouter = LLMRouter(
                resumeProviderPort = SampleResumeProviderPort(),
                meterRegistry = SimpleMeterRegistry()
            ),
            contextRetriever = ContextRetriever(
                resumePersistencePort = persistencePort,
                resumeProviderPort = SampleResumeProviderPort(),
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            promptTemplateService = FakePromptProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        service.streamChatResponse(
            StreamMessage(uuid = "user-4", type = MessageType.USER, content = "거기서 사용한 기술은?")
        ).toList()

        val finalPrompt = geminiPort.lastHistory.last().text
        assertTrue(finalPrompt.startsWith("RAG::"), "Expected RAG prompt for history follow-up, but was: $finalPrompt")
        assertTrue(persistencePort.lastQuery?.contains("지마켓") == true)
    }

    private class RecordingGeminiPort : GeminiPort {
        var lastHistory: List<ChatMessage> = emptyList()

        override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
            lastHistory = history
            return flowOf("응답")
        }

        override suspend fun generateContent(prompt: String, purpose: String): String = ""

        override suspend fun generateStyledImage(personImageFile: ByteArray, clothingImageFile: ByteArray): ByteArray =
            ByteArray(0)
    }

    private class InMemoryChatHistoryRepository : ChatHistoryRepository {
        private val storage = mutableMapOf<String, ChatHistory>()

        fun seed(userId: String, history: ChatHistory) {
            storage[userId] = history
        }

        override suspend fun findByUserId(userId: String): ChatHistory? = storage[userId]

        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            storage[chatHistory.userId] = chatHistory
            return chatHistory
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
}
