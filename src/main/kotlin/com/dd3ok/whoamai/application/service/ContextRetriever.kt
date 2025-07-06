package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * RAG 파이프라인을 위해 관련성 높은 컨텍스트(문서 조각)를 검색하는 책임을 가집니다.
 */
@Component
class ContextRetriever(
    private val resumePersistencePort: ResumePersistencePort,
    private val resumeProviderPort: ResumeProviderPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val rules: List<(String, String) -> String?> by lazy {
        listOf(
            { query, name -> if (listOf("누구야", "누구세요", "소개", "자기소개", name).any { query.contains(it) }) ChunkIdGenerator.forSummary() else null },
            { query, _ -> if (listOf("총 경력", "총경력", "전체경력").any { query.contains(it) }) ChunkIdGenerator.forTotalExperience() else null },
            { query, _ -> if (listOf("프로젝트", "project").any { query.contains(it) }) "projects" else null },
            { query, _ -> if (listOf("경력", "이력", "회사").any { query.contains(it) }) "experiences" else null },
            { query, _ -> if (listOf("자격증", "certificate").any { query.contains(it) }) ChunkIdGenerator.forCertificates() else null },
            { query, _ -> if (listOf("관심사", "interest").any { query.contains(it) }) ChunkIdGenerator.forInterests() else null },
            { query, _ -> if (listOf("기술", "스킬", "스택").any { query.contains(it) }) ChunkIdGenerator.forSkills() else null },
            { query, _ -> if (listOf("학력", "학교", "대학").any { query.contains(it) }) ChunkIdGenerator.forEducation() else null },
            { query, _ -> if (listOf("mbti", "성격").any { query.contains(it) }) ChunkIdGenerator.forMbti() else null },
            { query, _ -> if (listOf("취미", "여가시간").any { query.contains(it) }) ChunkIdGenerator.forHobbies() else null }
        )
    }

    suspend fun retrieve(userPrompt: String): List<String> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()
        val resumeName = resume.name.lowercase()

        // 1. 특정 프로젝트 제목 언급 시, 해당 프로젝트 정보 직접 반환
        val matchedProject = resume.projects.find { userPrompt.contains(it.title) }
        if (matchedProject != null) {
            logger.info("Topic detected: Specific Project ('${matchedProject.title}').")
            val projectId = ChunkIdGenerator.forProject(matchedProject.title)
            return resumePersistencePort.findContentById(projectId)?.let { listOf(it) } ?: emptyList()
        }

        // 2. 규칙 기반으로 Topic에 해당하는 정보 반환
        for (rule in rules) {
            val result = rule(normalizedQuery, resumeName)
            if (result != null) {
                logger.info("Topic detected by rule: $result")
                return when (result) {
                    "projects" -> resume.projects.mapNotNull {
                        resumePersistencePort.findContentById(ChunkIdGenerator.forProject(it.title))
                    }
                    "experiences" -> resume.experiences.mapNotNull {
                        resumePersistencePort.findContentById(ChunkIdGenerator.forExperience(it.company))
                    }
                    else -> resumePersistencePort.findContentById(result)?.let { listOf(it) } ?: emptyList()
                }
            }
        }

        // 3. 규칙에 해당하지 않으면 Vector 검색으로 유사 정보 탐색
        logger.info("No specific rules matched. Performing general vector search as a fallback.")
        return resumePersistencePort.searchSimilarSections(userPrompt, topK = 3)
    }
}