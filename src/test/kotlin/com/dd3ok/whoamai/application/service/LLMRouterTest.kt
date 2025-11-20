package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.ResumeEducation
import com.dd3ok.whoamai.domain.ResumeExperience
import com.dd3ok.whoamai.domain.ResumePeriod
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LLMRouterTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val resume = Resume(
        name = "유인재",
        summary = "백엔드 개발자",
        blog = "",
        experiences = listOf(
            ResumeExperience(
                company = "지마켓",
                period = ResumePeriod("2021-10", "2025-01"),
                position = "백엔드 개발자",
                description = "",
                projects = emptyList(),
                tags = emptyList()
            )
        ),
        skills = listOf("Spring Boot"),
        education = listOf(
            ResumeEducation(
                school = "KAIST",
                major = "컴퓨터공학",
                degree = "석사",
                period = ResumePeriod("2015-03", "2017-02")
            )
        )
    )

    private val fakeResumeProvider = object : ResumeProviderPort {
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    @Test
    fun `identity questions are hard-blocked to NON_RAG without LLM call`() = runTest {
        val fakeGemini = RecordingGeminiPort()
        val router = LLMRouter(
            geminiPort = fakeGemini,
            resumeProviderPort = fakeResumeProvider,
            promptTemplateService = FakePromptProvider,
            objectMapper = objectMapper
        )

        val decision = router.route("너는 뭐로 만들어졌어?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
        assertFalse(fakeGemini.called, "Gemini should not be called for hard-blocked prompts")
    }

    @Test
    fun `resume questions call LLM and return its decision`() = runTest {
        val fakeGemini = RecordingGeminiPort(
            returnJson = """{"queryType":"RESUME_RAG","company":"지마켓","skills":["Spring Boot"]}"""
        )
        val router = LLMRouter(
            geminiPort = fakeGemini,
            resumeProviderPort = fakeResumeProvider,
            promptTemplateService = FakePromptProvider,
            objectMapper = objectMapper
        )

        val decision = router.route("지마켓 경력 알려줘")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
        assertTrue(fakeGemini.called, "Gemini should be called for resume intent prompts")
    }

    private class RecordingGeminiPort(
        private val returnJson: String = ""
    ) : GeminiPort {
        var called: Boolean = false

        override suspend fun generateChatContent(history: List<com.dd3ok.whoamai.domain.ChatMessage>): kotlinx.coroutines.flow.Flow<String> {
            called = true
            throw UnsupportedOperationException("Not needed for test")
        }

        override suspend fun generateContent(prompt: String, purpose: String): String {
            called = true
            return returnJson
        }

        override suspend fun generateStyledImage(personImageFile: ByteArray, clothingImageFile: ByteArray): ByteArray {
            called = true
            throw UnsupportedOperationException("Not needed for test")
        }
    }

    private object FakePromptProvider : com.dd3ok.whoamai.common.service.PromptProvider {
        override fun systemInstruction(): String = ""
        override fun routingInstruction(): String = ""
        override fun renderRoutingTemplate(
            resumeOwnerName: String,
            companies: List<String>,
            skills: List<String>,
            question: String
        ): String = question

        override fun renderRagTemplate(context: String, question: String): String = question
        override fun renderConversationalTemplate(question: String): String = question
    }
}
