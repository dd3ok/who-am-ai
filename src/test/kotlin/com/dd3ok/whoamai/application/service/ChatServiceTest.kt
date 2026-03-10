package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.MessageType
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
