package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.service.ContextRetriever
import com.dd3ok.whoamai.application.service.LLMRouter
import com.dd3ok.whoamai.application.service.OwnDomainProfileProvider
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.domain.Resume
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CareerContextPlannerTest {

    @Test
    fun `rule context produces rag prompt plan`() = runTest {
        val planner = plannerWithChunks(
            mapOf(ChunkIdGenerator.forRecentActivities() to "recent activity context")
        )

        val plan = planner.plan(
            userPrompt = "퇴사 이후에는 뭐하고 지내나요?",
            history = emptyList()
        )

        assertTrue(plan.useRagPrompt)
        assertEquals("rule", plan.retrievalPath)
        assertEquals(listOf("recent activity context"), plan.contexts)
        assertTrue(plan.resumeQuestionDetected)
    }

    private fun plannerWithChunks(chunks: Map<String, String>): CareerContextPlanner {
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
            )
        )
    }

    private class SampleResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(
            name = "유인재",
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
