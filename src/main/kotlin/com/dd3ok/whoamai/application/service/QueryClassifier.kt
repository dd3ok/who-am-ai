package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import org.springframework.stereotype.Component

/**
 * 사용자의 질문 의도를 분류하는 책임을 가집니다.
 */
@Component
class QueryClassifier(
    private val resumeProviderPort: ResumeProviderPort
) {
    fun classify(userPrompt: String): QueryType {
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()
        val resume = resumeProviderPort.getResume()
        val resumeKeywords = listOf(
            "경력", "이력", "회사", "프로젝트", "스킬", "기술", "학력", "mbti", "취미", "이력서",
            resume.name.lowercase()
        )
        // 프로젝트 제목이 직접 언급되었는지 확인
        val isProjectMentioned = resume.projects.any { userPrompt.contains(it.title) }

        return if (isProjectMentioned || resumeKeywords.any { normalizedQuery.contains(it) }) {
            QueryType.RESUME_RAG
        } else {
            QueryType.NON_RAG
        }
    }
}