package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Project
import org.springframework.stereotype.Component

@Component
class CareerAgentToolFormatter {
    fun projects(projects: List<Project>): String {
        if (projects.isEmpty()) return "조건에 맞는 프로젝트 정보가 없습니다."
        return projects.joinToString("\n") { project ->
            "- ${project.title}: ${project.description} (기술: ${project.skills.joinToString(", ")})"
        }
    }

    fun experiences(experiences: List<Experience>): String {
        if (experiences.isEmpty()) return "조건에 맞는 경력 정보가 없습니다."
        return experiences.joinToString("\n") { experience ->
            "- ${experience.company}: ${experience.position}, ${experience.period.start}~${experience.period.end}"
        }
    }
}
