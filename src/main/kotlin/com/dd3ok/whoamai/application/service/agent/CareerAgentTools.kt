package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
class CareerAgentTools(
    private val resumeProviderPort: ResumeProviderPort,
    private val formatter: CareerAgentToolFormatter = CareerAgentToolFormatter(),
    private val meterRegistry: MeterRegistry = Metrics.globalRegistry
) {
    @Tool(
        name = "get_recent_activities",
        description = "퇴사 이후의 최근 활동, 개인 프로젝트, AI 에이전트 학습, 백엔드 시스템 설계 학습 내용을 조회한다."
    )
    fun getRecentActivities(): String {
        recordToolCall("get_recent_activities")
        return recentActivities()
    }

    @Tool(
        name = "find_projects",
        description = "기술, 회사, 태그 조건에 맞는 프로젝트를 조회한다. 조건이 없으면 전체 프로젝트를 반환한다."
    )
    fun findProjects(skill: String?, company: String?, tag: String?): String {
        recordToolCall("find_projects")
        val resume = resumeProviderPort.getResume()
        val projects = resume.projects.filter { project ->
            val skillMatches = skill.isNullOrBlank() || project.skills.any { it.contains(skill, ignoreCase = true) }
            val companyMatches = company.isNullOrBlank() || project.company.contains(company, ignoreCase = true)
            val tagMatches = tag.isNullOrBlank() || project.tags.any { it.contains(tag, ignoreCase = true) }
            skillMatches && companyMatches && tagMatches
        }
        return formatter.projects(projects)
    }

    @Tool(
        name = "find_experience",
        description = "회사명이나 별칭 조건에 맞는 경력 정보를 조회한다."
    )
    fun findExperience(company: String?): String {
        recordToolCall("find_experience")
        val resume = resumeProviderPort.getResume()
        val experiences = resume.experiences.filter { experience ->
            company.isNullOrBlank() ||
                experience.company.contains(company, ignoreCase = true) ||
                experience.aliases.any { it.contains(company, ignoreCase = true) }
        }
        return formatter.experiences(experiences)
    }

    @Tool(
        name = "get_backend_system_design_learning",
        description = "대용량 트래픽, 분산 환경 동시성, 원자성, 멱등성, 데이터 정합성, 캐싱, 큐 기반 아키텍처 학습 내용을 조회한다."
    )
    fun getBackendSystemDesignLearning(): String {
        recordToolCall("get_backend_system_design_learning")
        return recentActivities()
    }

    private fun recentActivities(): String {
        val resume = resumeProviderPort.getResume()
        return resume.recentActivities.ifBlank { "퇴사 이후 활동 정보가 아직 등록되어 있지 않습니다." }
    }

    private fun recordToolCall(tool: String) {
        meterRegistry.counter(
            "whoamai.agent.tool.call.total",
            "tool", tool
        ).increment()
    }
}
