package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.domain.Education
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Resume
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class LLMRouterEvalTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val resume = Resume(
        name = "유인재",
        summary = "백엔드 개발자",
        blog = "",
        experiences = listOf(
            Experience(
                company = "지마켓",
                aliases = listOf("G마켓", "gmarket"),
                period = Period("2021-10", "2025-01"),
                position = "백엔드 개발자",
                tags = listOf("결제", "인증")
            )
        ),
        skills = listOf("Spring Boot", "Kotlin", "MongoDB"),
        education = listOf(
            Education(
                school = "KAIST",
                major = "컴퓨터공학",
                degree = "석사",
                period = Period("2015-03", "2017-02")
            )
        ),
        interests = listOf("AI 서비스")
    )

    private val fakeResumeProvider = object : ResumeProviderPort {
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    @Test
    fun `router eval cases must satisfy expected route`() = runTest {
        val evalCases = loadCases()
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        for (case in evalCases) {
            val decision = router.route(case.question)

            assertEquals(
                QueryType.valueOf(case.expectedType),
                decision.queryType,
                "queryType mismatch for question='${case.question}'"
            )
            case.expectedCompany?.let {
                assertEquals(it, decision.company, "company mismatch for question='${case.question}'")
            }
            case.expectedSkill?.let {
                assertTrue(
                    decision.skills.orEmpty().contains(it),
                    "expected skill '$it' not found for question='${case.question}'"
                )
            }
        }
    }

    private fun loadCases(): List<RouterEvalCase> {
        val resource = ClassPathResource("evals/router-eval-cases.json")
        return resource.inputStream.use { objectMapper.readValue(it) }
    }

    private data class RouterEvalCase(
        val question: String,
        val expectedType: String,
        val expectedCompany: String? = null,
        val expectedSkill: String? = null
    )
}
