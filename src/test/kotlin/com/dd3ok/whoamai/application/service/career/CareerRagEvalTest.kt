package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.service.ContextRetriever
import com.dd3ok.whoamai.application.service.LLMRouter
import com.dd3ok.whoamai.application.service.OwnDomainProfileProvider
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Project
import com.dd3ok.whoamai.domain.Resume
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals

class CareerRagEvalTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `career rag eval cases pass deterministic retrieval and prompt gates`() = runTest {
        val cases = loadCases()
        val persistencePort = EvalResumePersistencePort()
        val resumeProviderPort = EvalResumeProviderPort()
        val planner = CareerContextPlanner(
            llmRouter = LLMRouter(resumeProviderPort, SimpleMeterRegistry()),
            contextRetriever = ContextRetriever(
                resumePersistencePort = persistencePort,
                resumeProviderPort = resumeProviderPort,
                ownDomainProfileProvider = OwnDomainProfileProvider(),
                metadataFilterEnabled = true
            ),
            meterRegistry = SimpleMeterRegistry(),
            conversationTopicPort = ConversationTopicPort { _, _, _ -> null }
        )
        val assembler = CareerPromptAssembler(EvalPromptProvider)

        cases.forEach { case ->
            val plan = planner.plan(case.question, emptyList())
            val finalPrompt = assembler.assemble(plan.prompt).last().text

            assertEquals(case.expectedPath, plan.retrieval.retrievalPath, "path mismatch for ${case.question}")
            assertEquals(case.expectRagPrompt, plan.prompt.useRagPrompt, "RAG mode mismatch for ${case.question}")
            case.mustContainAny.takeIf { it.isNotEmpty() }?.let { expected ->
                assertTrue(
                    expected.any(finalPrompt::contains),
                    "Expected one of $expected in prompt for ${case.question}, but was: $finalPrompt"
                )
            }
            case.mustNotContain.forEach { forbidden ->
                assertFalse(
                    finalPrompt.contains(forbidden),
                    "Forbidden text '$forbidden' found for ${case.question}: $finalPrompt"
                )
            }
        }
    }

    private fun loadCases(): List<CareerRagEvalCase> {
        val resource = ClassPathResource("evals/career-rag-eval-cases.json")
        return resource.inputStream.use { objectMapper.readValue(it) }
    }

    private data class CareerRagEvalCase(
        val question: String,
        val expectedPath: String,
        val expectRagPrompt: Boolean,
        val mustContainAny: List<String> = emptyList(),
        val mustNotContain: List<String> = emptyList()
    )

    private class EvalResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(
            name = "Injae Yoo",
            summary = "Backend engineer",
            experiences = listOf(
                Experience(
                    company = "Gmarket",
                    aliases = listOf("gmarket"),
                    period = Period("2021-10", "2025-01"),
                    position = "Backend engineer"
                )
            ),
            projects = listOf(
                Project(
                    title = "Settlement Platform",
                    company = "Gmarket",
                    period = Period("2024-01", "2024-12"),
                    skills = listOf("Spring Boot", "Kotlin")
                )
            ),
            skills = listOf("Spring Boot", "Kotlin", "MongoDB")
        )

        override fun isInitialized(): Boolean = true
    }

    private class EvalResumePersistencePort : ResumePersistencePort {
        override suspend fun index(resume: Resume): Int = 0
        override suspend fun findContentById(id: String): String? = null
        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> = emptyMap()

        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> =
            listOf(
                ResumeSearchResult(
                    chunkId = "experience_gmarket",
                    chunkType = "experience",
                    content = "Gmarket backend payments experience with Spring Boot."
                )
            )
    }

    private object EvalPromptProvider : PromptProvider {
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
