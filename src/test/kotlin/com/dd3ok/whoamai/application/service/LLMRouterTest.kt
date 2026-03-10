package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.domain.Education
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Resume
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LLMRouterTest {

    private val resume = Resume(
        name = "유인재",
        summary = "백엔드 개발자",
        blog = "",
        experiences = listOf(
            Experience(
                company = "지마켓",
                period = Period("2021-10", "2025-01"),
                position = "백엔드 개발자",
                tags = emptyList()
            )
        ),
        skills = listOf("Spring Boot"),
        education = listOf(
            Education(
                school = "KAIST",
                major = "컴퓨터공학",
                degree = "석사",
                period = Period("2015-03", "2017-02")
            )
        )
    )

    private val fakeResumeProvider = object : ResumeProviderPort {
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    @Test
    fun `identity questions are hard-blocked to NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("너는 뭐로 만들어졌어?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `resume questions are routed to RESUME_RAG by heuristic`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("지마켓 경력 알려줘")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `developer keyword inside resume question must not be hard-blocked`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("백엔드 개발자 경력을 알려줘")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `declarative chat mentioning resume must stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("어제 어떤 사람 이력서를 봤는데 별로였어")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `generic resume advice question must stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("이력서 어떻게 써?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `generic capability question without target must stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("보안 어떻게 공부해?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `targeted capability question should stay RESUME_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("유인재의 보안 경험 알려줘")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }
}
