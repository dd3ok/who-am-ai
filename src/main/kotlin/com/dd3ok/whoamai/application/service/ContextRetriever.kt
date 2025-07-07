package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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

    // The `routeDecision` is now just a "hint" for vector search.
    suspend fun retrieve(userPrompt: String, routeDecision: RouteDecision): List<String> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()
        val resumeName = resume.name.lowercase()

        val matchedProject = resume.projects.find { userPrompt.contains(it.title) }
        if (matchedProject != null) {
            logger.info("Context retrieved by: Specific Project Rule ('${matchedProject.title}').")
            val projectId = ChunkIdGenerator.forProject(matchedProject.title)
            return resumePersistencePort.findContentById(projectId)?.let { listOf(it) } ?: emptyList()
        }

        // 2. General rule-based check
        for (rule in rules) {
            val result = rule(normalizedQuery, resumeName)
            if (result != null) {
                logger.info("Context retrieved by: General Rule ('$result').")
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

        // 3. If no rules match, THEN consider the LLM Router's decision for vector search.
        if (routeDecision.queryType == QueryType.RESUME_RAG) {
            logger.info("No rules matched. Context retrieved by: Vector Search (as per LLMRouter).")
            val filters = mutableListOf<Document>()
            routeDecision.company?.let {
                filters.add(Document("company", Document("\$eq", it)))
            }
            routeDecision.skills?.takeIf { it.isNotEmpty() }?.let {
                filters.add(Document("skills", Document("\$in", it)))
            }

            val finalFilter = if (filters.isNotEmpty()) {
                Document("\$and", filters)
            } else {
                null
            }

            val searchKeywords = routeDecision.keywords?.joinToString(" ") ?: ""
            val finalQuery = "$userPrompt $searchKeywords".trim()

            return resumePersistencePort.searchSimilarSections(finalQuery, topK = 3, filter = finalFilter)
        }

        logger.info("No rules matched and LLMRouter classified as NON_RAG. No context will be used.")
        return emptyList()
    }
}