package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Project
import com.dd3ok.whoamai.domain.Resume
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CareerAgentToolsTest {

    @Test
    fun `recent activity tool returns resignation activity`() {
        val tools = CareerAgentTools(SampleResumeProviderPort())

        val result = tools.getRecentActivities()

        assertTrue(result.contains("AI 에이전트"))
        assertTrue(result.contains("백엔드 시스템 설계"))
    }

    @Test
    fun `project tool filters by skill`() {
        val tools = CareerAgentTools(SampleResumeProviderPort())

        val result = tools.findProjects(skill = "Spring Boot", company = null, tag = null)

        assertTrue(result.contains("who-am-ai"))
        assertTrue(result.contains("Spring Boot"))
    }

    @Test
    fun `system design tool returns backend capability summary`() {
        val tools = CareerAgentTools(SampleResumeProviderPort())

        val result = tools.getBackendSystemDesignLearning()

        assertTrue(result.contains("대용량 트래픽"))
        assertTrue(result.contains("멱등성"))
    }

    private class SampleResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(
            name = "유인재",
            recentActivities = "퇴사 이후에는 AI 에이전트와 백엔드 시스템 설계 역량을 중심으로 개인 프로젝트를 진행하고 있습니다. 대용량 트래픽 처리와 분산 환경의 동시성, 원자성, 멱등성도 학습하고 있습니다.",
            projects = listOf(
                Project(
                    title = "who-am-ai",
                    company = "개인",
                    period = Period("2026-06", "진행중"),
                    skills = listOf("Spring Boot", "Spring AI"),
                    tags = listOf("AI 에이전트"),
                    description = "이력 기반 Career Agent 서비스"
                )
            )
        )

        override fun isInitialized(): Boolean = true
    }
}
