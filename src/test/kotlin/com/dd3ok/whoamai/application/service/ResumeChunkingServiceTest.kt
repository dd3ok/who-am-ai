package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Project
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `chunk generation should propagate mapping failures`() {
        val service = ResumeChunkingService(FailingObjectMapper())

        val error = assertFailsWith<IllegalArgumentException> {
            service.generateChunks(Resume(name = "tester"))
        }

        assertEquals("conversion failed", error.message)
    }

    @Test
    fun `project evidence is included without requiring it on old records`() {
        val project = Project(
            title = "정산", problem = "플랫폼별 정책 차이",
            contribution = "전략 패턴으로 정책 분리", outcome = "응답 개선, 정량 수치 미기록",
            sourceUrl = "https://github.com/dd3ok/example"
        )
        val content = service.generateChunks(Resume(projects = listOf(project))).first { it.type == "project" }.content
        assertTrue(content.contains("해결할 문제: 플랫폼별 정책 차이"))
        assertTrue(content.contains("본인 기여: 전략 패턴으로 정책 분리"))
        assertTrue(content.contains("확인된 결과: 응답 개선, 정량 수치 미기록"))
        assertTrue(content.contains("저장소 링크: https://github.com/dd3ok/example"))
        val oldProject = jacksonObjectMapper().readValue("""{"title":"기존 프로젝트"}""", Project::class.java)
        assertEquals("", oldProject.contribution)
    }

    @Test
    fun `total experience counts shared months once and excludes gaps`() {
        val resume = Resume(experiences = listOf(
            Experience(company = "A", period = Period("2020-01", "2020-03")),
            Experience(company = "B", period = Period("2020-03", "2020-04")),
            Experience(company = "C", period = Period("2020-07", "2020-08"))
        ))
        val content = service.generateChunks(resume).first { it.id == ChunkIdGenerator.forTotalExperience() }.content
        assertTrue(content.contains("0년 6개월"))
        assertTrue(content.contains("입사월과 퇴사월을 포함"))
    }

    @Test
    fun `invalid experience dates do not produce a misleading partial total`() {
        val resume = Resume(experiences = listOf(
            Experience(company = "A", period = Period("2020-01", "2020-03")),
            Experience(company = "B", period = Period("2021-01", ""))
        ))
        val content = service.generateChunks(resume).first { it.id == ChunkIdGenerator.forTotalExperience() }.content
        assertTrue(content.contains("날짜 정보가 부족합니다"))
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("총 경력은"))
    }

    private class FailingObjectMapper : ObjectMapper() {
        override fun <T> convertValue(fromValue: Any?, toValueRef: TypeReference<T>): T {
            throw IllegalArgumentException("conversion failed")
        }
    }
}
