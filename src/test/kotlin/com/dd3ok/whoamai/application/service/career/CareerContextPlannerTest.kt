package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.port.out.PortfolioCatalogSelectorPort
import com.dd3ok.whoamai.application.port.out.PortfolioSelection
import com.dd3ok.whoamai.application.port.out.PortfolioSelectionIntent
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
    fun `education follow up keeps its source after successful topic selection`() = runTest {
        val planner = plannerWithChunks(mapOf("education" to "2017년 2월 졸업"), ConversationTopicPort { _, _, topics ->
            topics.single()
        })
        val history = listOf(ChatMessage("user", "학력과 전공 알려줘"))
        assertEquals(listOf("education"), planner.plan(history.single().text, emptyList()).prompt.sources.map { it.chunkId })
        val plan = planner.plan("몇 년도에 졸업했어?", history)
        assertEquals(listOf("education"), plan.prompt.sources.map { it.chunkId })
        assertTrue(plan.prompt.useRagPrompt)
    }

    @Test
    fun `unlisted follow up wording preserves the project source through multiple turns`() = runTest {
        val project = "ESM 로그인 개편"
        val id = ChunkIdGenerator.forProject(project)
        val planner = plannerWithChunks(mapOf(id to "로그인 개편 자료"), ConversationTopicPort { _, history, topics ->
            assertTrue(history.all { it.role == "user" })
            assertEquals(listOf(project, "기술 스택"), topics.map { it.title })
            topics.single { it.title == project }
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
            assertEquals(listOf("ESM 로그인 개편"), topics.map { it.title })
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
        val planner = plannerWithChunks(emptyMap(), ConversationTopicPort { _, _, topics ->
            topics.single().copy(sourceIds = listOf("invented_source"))
        })
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

    @Test
    fun `profile subjects remain distinct across transitions and repeated follow ups`() = runTest {
        val chunks = mapOf("education" to "학력 자료", "certificates" to "자격증 자료", "recent_activities" to "최근 활동 자료")
        for ((title, id, history, question) in listOf(
            listOf("학력", "education", "학력과 전공 알려줘", "그건 몇 년도에 끝났어?"),
            listOf("학력", "education", "학력과 전공 알려줘", "유인재님은 몇 년도에 졸업했어?"),
            listOf("자격증", "certificates", "자격증 알려줘", "언제 땄어?"),
            listOf("최근 활동", "recent_activities", "퇴사 이후에는 뭐해?", "요즘도 계속해?")
        )) {
            val planner = plannerWithChunks(chunks, ConversationTopicPort { _, _, topics -> topics.single { it.title == title } })
            val turns = listOf(ChatMessage("user", "ESM 로그인 개편 설명해줘"), ChatMessage("user", history))
            val plan = planner.plan(question, turns)
            assertEquals(listOf(id), plan.prompt.sources.map { it.chunkId }, question)
            assertEquals("$title — $question", plan.prompt.userPrompt)
            assertTrue(plan.prompt.useRagPrompt)
        }
    }

    @Test
    fun `a selected but unavailable profile source stays in the missing evidence path`() = runTest {
        val planner = plannerWithChunks(emptyMap(), ConversationTopicPort { _, _, topics -> topics.single() })
        val plan = planner.plan("몇 년도에 졸업했어?", listOf(ChatMessage("user", "학력 알려줘")))
        assertEquals("rag_empty", plan.retrieval.retrievalPath)
        assertTrue(plan.retrieval.resumeQuestionDetected)
        assertTrue(plan.prompt.useRagPrompt)
        assertTrue(plan.prompt.sources.isEmpty())
    }

    @Test
    fun `profile topics come only from user turns and reuse existing section rules`() = runTest {
        val planner = plannerWithChunks(mapOf("education" to "학력 자료", "certificates" to "자격증 자료"),
            ConversationTopicPort { _, history, topics ->
                assertTrue(history.all { it.role == "user" })
                assertEquals(listOf("학력", "자격증"), topics.map { it.title })
                topics.single { it.title == "학력" }
            })
        val plan = planner.plan("몇 년도에 졸업했어?", listOf(
            ChatMessage("user", "학력 알려줘"), ChatMessage("model", "취미도 있습니다."), ChatMessage("user", "자격증 알려줘")
        ))
        assertEquals(listOf("education"), plan.prompt.sources.map { it.chunkId })
    }

    @Test
    fun `standalone recent activity question does not inherit an earlier own-service topic`() = runTest {
        val planner = plannerWithChunks(
            mapOf(
                ChunkIdGenerator.forRecentActivities() to "퇴사 이후 파이프라인 및 시스템 디자인 학습",
                ChunkIdGenerator.forProject("ESM 로그인 개편") to "ESM 로그인 개편 자료"
            ),
            ConversationTopicPort { _, _, topics -> topics.first() }
        )
        val history = listOf(
            ChatMessage("user", "who-am-ai는 어떤 구조로 만들었어?"),
            ChatMessage("model", "who-am-ai 구현 안내")
        )
        val plan = planner.plan("퇴사 이후 최근에는 어떤 프로젝트와 공부를 하고 있나요?", history)
        assertTrue(plan.prompt.sources.any { it.chunkId == ChunkIdGenerator.forRecentActivities() })
        assertTrue(plan.prompt.sources.none { it.chunkId == "service_profile" })
    }

    @Test
    fun `tier 1 rule match bypasses catalog selector without extra LLM call`() = runTest {
        var selectorCalled = false
        val planner = plannerWithChunks(
            chunks = mapOf(ChunkIdGenerator.forRecentActivities() to "퇴사 이후 학습 내용"),
            selectorPort = PortfolioCatalogSelectorPort { _, _, _ ->
                selectorCalled = true
                error("should not invoke selector when Tier 1 matches")
            }
        )

        val plan = planner.plan("퇴사 이후 최근에 뭐 공부하고 있어?", emptyList())

        assertEquals("rule", plan.retrieval.retrievalPath)
        assertTrue(!selectorCalled)
        assertTrue(plan.prompt.useRagPrompt)
    }

    @Test
    fun `tier 2 selector resolves portfolio chunks missing evidence and general chat while filtering assistant turns`() = runTest {
        val projectChunkId = ChunkIdGenerator.forProject("ESM 로그인 개편")
        var observedRoles: List<String> = emptyList()
        val planner = plannerWithChunks(
            chunks = mapOf(projectChunkId to "GeoIP 기반 접속 차단 구현 상세"),
            selectorPort = PortfolioCatalogSelectorPort { question, history, _ ->
                observedRoles = history.map { it.role }
                when {
                    question.contains("GeoIP") -> PortfolioSelection(PortfolioSelectionIntent.PORTFOLIO_ANSWER, listOf(projectChunkId))
                    question.contains("결혼") -> PortfolioSelection(PortfolioSelectionIntent.MISSING_EVIDENCE)
                    else -> PortfolioSelection(PortfolioSelectionIntent.GENERAL_CHAT)
                }
            }
        )
        val mixedHistory = listOf(
            ChatMessage("user", "이전에 만든 서비스가 뭐야?"),
            ChatMessage("model", "assistant 가짜 사실"),
            ChatMessage("user", "ESM 이야기 이어서 할게")
        )

        val answerPlan = planner.plan("GeoIP 연동은 어떻게 처리했어?", mixedHistory)
        assertEquals("selector", answerPlan.retrieval.retrievalPath)
        assertTrue(answerPlan.prompt.useRagPrompt)
        assertEquals(listOf(projectChunkId), answerPlan.prompt.sources.map { it.chunkId })
        assertEquals(listOf("user", "user"), observedRoles)

        val missingPlan = planner.plan("혹시 결혼은 하셨나요?", mixedHistory)
        assertEquals("rag_empty", missingPlan.retrieval.retrievalPath)
        assertTrue(missingPlan.prompt.useRagPrompt)
        assertTrue(missingPlan.retrieval.resumeQuestionDetected)
        assertTrue(missingPlan.prompt.sources.isEmpty())

        val chatPlan = planner.plan("점심 뭐 먹을까?", mixedHistory)
        assertEquals("selector_chat", chatPlan.retrieval.retrievalPath)
        assertTrue(!chatPlan.prompt.useRagPrompt)
        assertTrue(!chatPlan.retrieval.resumeQuestionDetected)
        assertTrue(chatPlan.prompt.sources.isEmpty())
    }

    @Test
    fun `tier 2 selector exception falls back cleanly to router path`() = runTest {
        val planner = plannerWithChunks(
            chunks = emptyMap(),
            selectorPort = PortfolioCatalogSelectorPort { _, _, _ -> throw IllegalStateException("selector unavailable") }
        )

        val plan = planner.plan("오늘 날씨 어때?", emptyList())

        assertEquals("non_rag", plan.retrieval.retrievalPath)
        assertTrue(!plan.prompt.useRagPrompt)
    }

    @Test
    fun `service DB and conversation history questions bypass prior project topic and route to service_profile`() = runTest {
        val bqmsId = ChunkIdGenerator.forProject("삼성전자 입찰견적관리시스템(BQMS) – MRO 몰/2.0")
        val bissId = ChunkIdGenerator.forProject("삼성전자로지텍 설치지원시스템(BISS) 개편")
        val skillsId = ChunkIdGenerator.forProject("spring-engineering-skills")
        val savepointId = ChunkIdGenerator.forProject("savepoint")
        val customResume = Resume(
            name = "유인재",
            projects = listOf(
                Project(title = "삼성전자 입찰견적관리시스템(BQMS) – MRO 몰/2.0", aliases = listOf("BQMS")),
                Project(title = "삼성전자로지텍 설치지원시스템(BISS) 개편", aliases = listOf("BISS")),
                Project(title = "spring-engineering-skills"),
                Project(title = "savepoint")
            )
        )
        val provider = object : ResumeProviderPort {
            override fun getResume(): Resume = customResume
            override fun isInitialized(): Boolean = true
        }
        val planner = CareerContextPlanner(
            llmRouter = LLMRouter(provider, SimpleMeterRegistry()),
            contextRetriever = ContextRetriever(
                resumePersistencePort = ChunkResumePersistencePort(
                    mapOf(
                        bqmsId to "BQMS 자료",
                        bissId to "BISS 자료",
                        skillsId to "spring-engineering-skills 자료",
                        savepointId to "savepoint 자료"
                    )
                ),
                resumeProviderPort = provider,
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            meterRegistry = SimpleMeterRegistry(),
            conversationTopicPort = ConversationTopicPort { _, _, _ -> error("Should not delegate own-service question to prior project topic") }
        )

        val baseHistory = listOf(
            ChatMessage("user", "삼성전자 BQMS와 BISS 개발·운영 경험을 알려주세요"),
            ChatMessage("model", "BQMS와 BISS 경험 안내"),
            ChatMessage("user", "spring-engineering-skills는 어떤 프로젝트인가요?"),
            ChatMessage("model", "spring-engineering-skills 안내")
        )

        val q3 = "이 질문들은 DB에서 가져오나요?"
        val plan3 = planner.plan(q3, baseHistory)
        assertEquals("rule", plan3.retrieval.retrievalPath)
        assertEquals(listOf("service_profile"), plan3.prompt.sources.map { it.chunkId })
        assertEquals(q3, plan3.prompt.userPrompt)
        assertTrue(plan3.prompt.contexts.single().contains("MongoChatAnswerCacheAdapter"))

        val q4 = "현재 대화 내용들은 DB를ㅈ ㅗ회하나요?"
        for (history in listOf(baseHistory, baseHistory + listOf(ChatMessage("user", q3), ChatMessage("model", "답변 안내")))) {
            val plan4 = planner.plan(q4, history)
            assertEquals("rule", plan4.retrieval.retrievalPath)
            assertEquals(listOf("service_profile"), plan4.prompt.sources.map { it.chunkId })
            assertEquals(q4, plan4.prompt.userPrompt)
            assertTrue(plan4.prompt.contexts.single().contains("chat_histories"))
        }

        val savepointPlan = planner.plan("savepoint는 대화 내용을 DB에 저장하나요?", baseHistory)
        assertEquals(listOf(savepointId), savepointPlan.prompt.sources.map { it.chunkId })
    }

    private fun plannerWithChunks(
        chunks: Map<String, String>,
        topicPort: ConversationTopicPort = ConversationTopicPort { _, _, _ -> null },
        selectorPort: PortfolioCatalogSelectorPort = PortfolioCatalogSelectorPort { _, _, _ -> null }
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
            conversationTopicPort = topicPort,
            portfolioCatalogSelectorPort = selectorPort
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
