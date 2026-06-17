package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Project
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResumeChunkingServiceTest {

    private val service = ResumeChunkingService(jacksonObjectMapper())

    @Test
    fun `experience chunk should include tags and role oriented guidance`() {
        val resume = Resume(
            experiences = listOf(
                Experience(
                    company = "지마켓",
                    period = Period("2021-10", "2025-01"),
                    position = "백엔드 개발자",
                    tags = listOf("결제", "보안", "협업")
                )
            )
        )

        val experienceChunk = service.generateChunks(resume)
            .first { it.type == "experience" }

        assertTrue(experienceChunk.content.contains("결제, 보안, 협업"))
        assertTrue(experienceChunk.content.contains("역할"))
        assertTrue(experienceChunk.content.contains("문제 해결"))
    }

    @Test
    fun `project chunk should include description skills and tags`() {
        val resume = Resume(
            projects = listOf(
                Project(
                    title = "정산 플랫폼 고도화",
                    company = "지마켓",
                    period = Period("2023-01", "2023-12"),
                    description = "정산 배치와 운영 화면을 개선했습니다.",
                    skills = listOf("Kotlin", "Spring Boot"),
                    tags = listOf("배치", "품질", "아키텍처")
                )
            )
        )

        val projectChunk = service.generateChunks(resume)
            .first { it.type == "project" }

        assertTrue(projectChunk.content.contains("정산 배치와 운영 화면을 개선했습니다."))
        assertTrue(projectChunk.content.contains("Kotlin, Spring Boot"))
        assertTrue(projectChunk.content.contains("배치, 품질, 아키텍처"))
    }

    @Test
    fun `recent activities chunk should include post resignation work and system design learning`() {
        val resume = Resume(
            recentActivities = "퇴사 이후에는 AI 에이전트와 백엔드 시스템 설계 역량을 중심으로 개인 프로젝트를 진행하고 있습니다. " +
                "주식 데이터 수집, 예측, 평가, 텔레그램 브리핑까지 이어지는 Python 기반 파이프라인을 만들고 있습니다. " +
                "대규모 트래픽 처리, 분산 환경의 동시성·원자성·멱등성, 데이터 정합성, 캐싱과 큐 기반 아키텍처 등 시스템 디자인 역량도 학습하고 있습니다."
        )

        val recentActivitiesChunk = service.generateChunks(resume)
            .first { it.id == ChunkIdGenerator.forRecentActivities() }

        assertTrue(recentActivitiesChunk.content.contains("퇴사 이후"))
        assertTrue(recentActivitiesChunk.content.contains("Python 기반 파이프라인"))
        assertTrue(recentActivitiesChunk.content.contains("시스템 디자인"))
    }
}
