package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Project
import com.dd3ok.whoamai.domain.Resume
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResumeChunkingServiceTest {

    private val service = ResumeChunkingService(ObjectMapper())

    @Test
    fun `experience chunk inherits project skills`() {
        val resume = Resume(
            name = "유인재",
            experiences = listOf(
                Experience(
                    company = "지마켓",
                    period = Period("2023-01", "2024-01"),
                    position = "백엔드 개발자",
                    tags = listOf("MSA")
                )
            ),
            projects = listOf(
                Project(
                    title = "ESM 로그인 개편",
                    company = "지마켓",
                    period = Period("2023-03", "2023-08"),
                    skills = listOf("Kafka", "Spring Boot"),
                    description = "로그인 개편"
                )
            )
        )

        val chunks = service.generateChunks(resume)
        val experienceChunk = chunks.find { it.id == ChunkIdGenerator.forExperience("지마켓") }

        assertNotNull(experienceChunk)
        assertTrue(experienceChunk.skills?.contains("Kafka") == true)
        assertTrue(experienceChunk.skills?.contains("MSA") == true)
    }
}
