package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.service.ContextRetriever
import com.dd3ok.whoamai.application.service.LLMRouter
import com.dd3ok.whoamai.application.service.OwnDomainProfileProvider
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.Project
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CareerContextPlannerTest {

    @Test
    fun `unlisted follow up wording preserves the project source through multiple turns`() = runTest {
        val project = "ESM 로그인 개편"
        val id = ChunkIdGenerator.forProject(project)
        val planner = plannerWithChunks(mapOf(id to "로그인 개편 자료"), ConversationTopicPort { _, history, topics ->
            assertTrue(history.all { it.role == "user" })
            assertEquals(listOf(project), topics)
            project
        })
        val history = listOf(
            ChatMessage("user", "$project 설명해줘"),
            ChatMessage("model", "로그인과 인증을 개편했습니다."),
            ChatMessage("user", "그럼 사용한 기술은?"),
            ChatMessage("model", "Spring Boot와 Redis입니다.")
        )
        for (question in listOf("그럼 응답 시간이 몇 퍼센트 줄었어?", "얼마나 빨라졌어?", "한 번에 몇 명까지 받을 수 있어?")) {
            val plan = planner.plan(question, history)
            assertEquals(listOf(id), plan.prompt.sources.map { it.chunkId }, question)
            assertEquals("$project — $question", plan.prompt.userPrompt)
        }
    }

    @Test
    fun `rule context produces rag prompt plan`() = runTest {
        val planner = plannerWithChunks(
            mapOf(ChunkIdGenerator.forRecentActivities() to "recent activity context")
        )

        val plan = planner.plan(
            userPrompt = "퇴사 이후에는 뭐하고 지내나요?",
            history = emptyList()
        )

        assertTrue(plan.prompt.useRagPrompt)
        assertEquals("rule", plan.retrieval.retrievalPath)
        assertEquals(listOf("recent activity context"), plan.prompt.contexts)
        assertTrue(plan.retrieval.resumeQuestionDetected)
    }

    @Test
    fun `independent question keeps its own subject when semantic resolution declines inheritance`() = runTest {
        val planner = plannerWithChunks(emptyMap(), ConversationTopicPort { _, history, topics ->
            assertEquals(listOf("ESM 로그인 개편"), topics)
            assertTrue(history.none { it.role == "model" })
            null
        })
        val history = listOf(ChatMessage("user", "ESM 로그인 개편 설명해줘"),
            ChatMessage("model", "다른 가상의 프로젝트에 관한 주장"))
        for (question in listOf("그럼 오늘 날씨 알려줘", "그럼 당근은 얼마나 빨라졌어?")) {
            val plan = planner.plan(question, history)
            assertEquals(question, plan.prompt.userPrompt)
            assertTrue(plan.prompt.sources.isEmpty())
            assertEquals("non_rag", plan.retrieval.retrievalPath)
        }
    }

    @Test
    fun `assistant claims and expired user topics cannot establish a semantic candidate`() = runTest {
        val planner = plannerWithChunks(emptyMap(), ConversationTopicPort { _, _, _ -> error("No valid candidate") })
        val assistantOnly = listOf(ChatMessage("model", "ESM 로그인 개편을 했습니다."))
        val expired = listOf(ChatMessage("user", "ESM 로그인 개편 설명해줘")) +
            List(6) { ChatMessage("user", "오늘 날씨 알려줘") }
        for (history in listOf(emptyList(), assistantOnly, expired)) {
            val question = "얼마나 빨라졌어?"
            assertEquals(question, planner.plan(question, history).prompt.userPrompt)
        }
    }

    @Test
    fun `unknown semantic topic is rejected before retrieval`() = runTest {
        val planner = plannerWithChunks(emptyMap(), ConversationTopicPort { _, _, _ -> "가상의 프로젝트" })
        assertFailsWith<IllegalStateException> {
            planner.plan("얼마나 빨라졌어?", listOf(ChatMessage("user", "ESM 로그인 개편 설명해줘")))
        }
    }

    @Test
    fun `resolution failures do not silently produce an ungrounded response`() = runTest {
        for (failure in listOf(IllegalStateException("provider unavailable"), kotlinx.coroutines.CancellationException("cancelled"))) {
            val planner = plannerWithChunks(emptyMap(), ConversationTopicPort { _, _, _ -> throw failure })
            val error = assertFailsWith<Exception> {
                planner.plan("얼마나 빨라졌어?", listOf(ChatMessage("user", "ESM 로그인 개편 설명해줘")))
            }
            assertEquals(failure, error)
        }
    }

    @Test
    fun `known subjects and explicit follow ups keep the existing path without a second model call`() = runTest {
        val id = ChunkIdGenerator.forProject("ESM 로그인 개편")
        val planner = plannerWithChunks(mapOf(id to "로그인 자료"), ConversationTopicPort { _, _, _ -> error("Unnecessary resolution") })
        val history = listOf(ChatMessage("user", "ESM 로그인 개편 설명해줘"))
        for (question in listOf("ESM 로그인 개편 기간은?", "그럼 사용한 기술은?")) {
            assertEquals(listOf(id), planner.plan(question, history).prompt.sources.map { it.chunkId })
        }
    }

    private fun plannerWithChunks(
        chunks: Map<String, String>,
        topicPort: ConversationTopicPort = ConversationTopicPort { _, _, _ -> null }
    ): CareerContextPlanner {
        val resumeProviderPort = SampleResumeProviderPort()
        return CareerContextPlanner(
            llmRouter = LLMRouter(
                resumeProviderPort = resumeProviderPort,
                meterRegistry = SimpleMeterRegistry()
            ),
            contextRetriever = ContextRetriever(
                resumePersistencePort = ChunkResumePersistencePort(chunks),
                resumeProviderPort = resumeProviderPort,
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            meterRegistry = SimpleMeterRegistry(),
            conversationTopicPort = topicPort
        )
    }

    private class SampleResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(
            name = "유인재",
            projects = listOf(Project(title = "ESM 로그인 개편")),
            recentActivities = "퇴사 이후에는 AI 에이전트와 백엔드 시스템 설계를 공부하고 있습니다."
        )

        override fun isInitialized(): Boolean = true
    }

    private class ChunkResumePersistencePort(
        private val chunks: Map<String, String>
    ) : ResumePersistencePort {
        override suspend fun index(resume: Resume): Int = 0
        override suspend fun findContentById(id: String): String? = chunks[id]
        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> =
            chunks.filterKeys(ids::contains)

        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> = emptyList()
    }
}
