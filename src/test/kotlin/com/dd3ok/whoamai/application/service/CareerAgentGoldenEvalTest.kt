package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.service.agent.CareerContextPlanner
import com.dd3ok.whoamai.application.service.agent.CareerPromptAssembler
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.StreamMessage
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CareerAgentGoldenEvalTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `career agent answers contain required phrases and avoid unsupported live github claims`() = runTest {
        val cases = objectMapper.readValue(
            ClassPathResource("evals/career-agent-golden-cases.json").inputStream,
            object : TypeReference<List<CareerAgentGoldenCase>>() {}
        )

        cases.forEach { case ->
            val answer = answerCase(case.question)

            case.requiredPhrases.forEach { phrase ->
                assertTrue(answer.contains(phrase), "case=${case.id}, missing=$phrase, answer=$answer")
            }
            case.forbiddenPhrases.forEach { phrase ->
                assertFalse(answer.contains(phrase), "case=${case.id}, forbidden=$phrase, answer=$answer")
            }
        }
    }

    private suspend fun answerCase(question: String): String {
        val resume = Resume(
            name = "유인재",
            recentActivities = "퇴사 이후에는 AI 에이전트와 백엔드 시스템 설계 역량을 중심으로 개인 프로젝트를 진행하고 있습니다. who-am-ai, WATCHLIST.md, savepoint, lucid 같은 저장소를 만들며 LLM 기반 서비스와 AI 에이전트 개발 도구를 직접 설계하고 있습니다. 대용량 트래픽 처리, 분산 환경의 동시성·원자성·멱등성, 데이터 정합성, 캐싱과 큐 기반 아키텍처도 학습하고 있습니다.",
            skills = listOf("Spring Boot", "Spring AI")
        )
        val resumeProvider = StaticResumeProviderPort(resume)
        val context = resume.recentActivities
        val chatService = ChatService(
            geminiPort = EchoPromptGeminiPort(),
            chatHistoryRepository = InMemoryChatHistoryRepository(),
            careerContextPlanner = CareerContextPlanner(
                llmRouter = LLMRouter(resumeProvider, SimpleMeterRegistry()),
                contextRetriever = ContextRetriever(
                    resumePersistencePort = StaticResumePersistencePort(context),
                    resumeProviderPort = resumeProvider,
                    ownDomainProfileProvider = OwnDomainProfileProvider(),
                    metadataFilterEnabled = true
                )
            ),
            careerPromptAssembler = CareerPromptAssembler(FakePromptProvider),
            meterRegistry = SimpleMeterRegistry()
        )

        return chatService.streamChatResponse(
            StreamMessage(uuid = "golden-${question.hashCode()}", type = MessageType.USER, content = question)
        ).toList().joinToString("")
    }

    private data class CareerAgentGoldenCase(
        val id: String,
        val question: String,
        val requiredPhrases: List<String>,
        val forbiddenPhrases: List<String>
    )

    private class EchoPromptGeminiPort : GeminiPort {
        override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> =
            flowOf(history.last().text)

        override suspend fun generateContent(prompt: String, purpose: String): String = prompt
    }

    private class InMemoryChatHistoryRepository : ChatHistoryRepository {
        private val storage = mutableMapOf<String, ChatHistory>()

        override suspend fun findByUserId(userId: String): ChatHistory? = storage[userId]

        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            storage[chatHistory.userId] = chatHistory
            return chatHistory
        }
    }

    private class StaticResumeProviderPort(private val resume: Resume) : ResumeProviderPort {
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    private class StaticResumePersistencePort(private val context: String) : ResumePersistencePort {
        override suspend fun index(resume: Resume): Int = 0
        override suspend fun findContentById(id: String): String? = context

        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> =
            ids.associateWith {
                if (it == ChunkIdGenerator.forRecentActivities()) context else context
            }

        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> =
            listOf(
                ResumeSearchResult(
                    chunkId = ChunkIdGenerator.forRecentActivities(),
                    chunkType = "recent_activity",
                    content = context
                )
            )
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
