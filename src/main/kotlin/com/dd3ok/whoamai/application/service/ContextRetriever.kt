package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import org.slf4j.LoggerFactory
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.stereotype.Component

/**
 * 작업 목적: 규칙 기반 및 벡터 검색을 통해 사용자 프롬프트에 적합한 이력서 컨텍스트를 조회한다.
 * 주요 로직: 명시적 규칙으로 우선 검색 후, 필요 시 Spring AI VectorStore 필터 표현식을 조합해 유사도 검색을 위임한다.
 */
@Component
class ContextRetriever(
    private val resumePersistencePort: ResumePersistencePort,
    private val resumeProviderPort: ResumeProviderPort
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val filterExpressionBuilder = FilterExpressionBuilder()
    private val whitespaceRegex = Regex("\\s+")

    private val rules: List<(RuleContext) -> String?> by lazy {
        listOf(
            { ctx ->
                if (ctx.matchesAny(listOf("누구야", "누구세요", "소개", "소개해", "자기소개", "설명", "알려줘", ctx.resumeName))) ChunkIdGenerator.forSummary() else null
            },
            { ctx -> if (ctx.matchesAny(listOf("총 경력", "총경력", "전체경력"))) ChunkIdGenerator.forTotalExperience() else null },
            { ctx -> if (ctx.matchesAny(listOf("프로젝트", "project"))) "projects" else null },
            { ctx -> if (ctx.matchesAny(listOf("경력", "이력", "회사"))) "experiences" else null },
            { ctx -> if (ctx.matchesAny(listOf("자격증", "certificate"))) ChunkIdGenerator.forCertificates() else null },
            { ctx -> if (ctx.matchesAny(listOf("관심사", "interest"))) ChunkIdGenerator.forInterests() else null },
            { ctx -> if (ctx.matchesAny(listOf("기술", "스킬", "스택"))) ChunkIdGenerator.forSkills() else null },
            { ctx -> if (ctx.matchesAny(listOf("학력", "학교", "대학"))) ChunkIdGenerator.forEducation() else null },
            { ctx -> if (ctx.matchesAny(listOf("mbti", "성격"))) ChunkIdGenerator.forMbti() else null },
            { ctx -> if (ctx.matchesAny(listOf("취미", "여가시간"))) ChunkIdGenerator.forHobbies() else null }
        )
    }

    /**
     * 규칙 기반으로 컨텍스트를 검색합니다.
     * @return 컨텍스트를 찾으면 List<String>, 못 찾으면 빈 List를 반환합니다.
     */
    suspend fun retrieveByRule(userPrompt: String): List<String> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = userPrompt.lowercase()
        val condensedQuery = normalizedQuery.replace(whitespaceRegex, "")
        val resumeName = resume.name.lowercase()
        val ruleContext = RuleContext(
            normalizedQuery = normalizedQuery,
            condensedQuery = condensedQuery,
            resumeName = resumeName,
            resumeNameFragments = NameFragmentExtractor.extract(resume.name)
        )

        // 1. Specific project title check
        val matchedProject = resume.projects.find { normalizedQuery.contains(it.title.lowercase()) }
        if (matchedProject != null) {
            logger.info("Context retrieved by: Specific Project Rule ('${matchedProject.title}').")
            val projectId = ChunkIdGenerator.forProject(matchedProject.title)
            return resumePersistencePort.findContentById(projectId)?.let { listOf(it) } ?: emptyList()
        }

        // 2. General rule-based check
        for (rule in rules) {
            val result = rule(ruleContext)
            if (result != null) {
                logger.info("Context retrieved by: General Rule ('$result').")
                return when (result) {
                    "projects" -> {
                        val projectIds = resume.projects.map { ChunkIdGenerator.forProject(it.title) }
                        val contentsById = resumePersistencePort.findContentsByIds(projectIds)
                        projectIds.mapNotNull(contentsById::get)
                    }
                    "experiences" -> {
                        val experienceIds = resume.experiences.map { ChunkIdGenerator.forExperience(it.company) }
                        val contentsById = resumePersistencePort.findContentsByIds(experienceIds)
                        experienceIds.mapNotNull(contentsById::get)
                    }
                    else -> resumePersistencePort.findContentById(result)?.let { listOf(it) } ?: emptyList()
                }
            }
        }

        return emptyList() // 규칙에 맞는 것이 없으면 빈 리스트 반환
    }

    /**
     * Intent Decider가 전달한 힌트를 바탕으로 벡터 검색을 수행합니다.
     */
    suspend fun retrieveByVector(userPrompt: String, routeDecision: RouteDecision): List<String> {
        logger.info("No rules matched. Context retrieved by: Vector Search (intent-driven).")
        val filterOps = mutableListOf<FilterExpressionBuilder.Op>()
        routeDecision.company?.let {
            filterOps.add(filterExpressionBuilder.eq("company", it))
        }
        var skillFilterOp: FilterExpressionBuilder.Op? = null
        routeDecision.skills?.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }?.takeIf { it.isNotEmpty() }?.let { skills ->
            skillFilterOp = filterExpressionBuilder.`in`("skills", skills)
            filterOps.add(skillFilterOp!!)
        }

        val finalFilter = buildFilterExpression(filterOps)

        val searchKeywords = routeDecision.keywords?.joinToString(" ") ?: ""
        val finalQuery = "$userPrompt $searchKeywords".trim()

        var results = resumePersistencePort.searchSimilarSections(finalQuery, topK = 3, filter = finalFilter)
        if (results.isEmpty() && skillFilterOp != null) {
            val fallbackFilter = buildFilterExpression(filterOps.filterNot { it == skillFilterOp })
            results = resumePersistencePort.searchSimilarSections(finalQuery, topK = 3, filter = fallbackFilter)
        }
        return results
    }

    private data class RuleContext(
        val normalizedQuery: String,
        val condensedQuery: String,
        val resumeName: String,
        val resumeNameFragments: List<String>
    )

    private fun RuleContext.matchesAny(candidates: List<String>): Boolean =
        candidates.any { containsKeyword(it) }

    private fun RuleContext.containsKeyword(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val normalizedCandidate = candidate.lowercase()
        val condensedCandidate = normalizedCandidate.replace(whitespaceRegex, "")
        if (normalizedCandidate == resumeName) {
            return resumeNameFragments.any { fragment -> normalizedQuery.contains(fragment) }
        }
        return normalizedQuery.contains(normalizedCandidate) || condensedQuery.contains(condensedCandidate)
    }

    private fun buildFilterExpression(ops: List<FilterExpressionBuilder.Op>): Filter.Expression? {
        if (ops.isEmpty()) return null
        var current = ops.first()
        for (index in 1 until ops.size) {
            current = filterExpressionBuilder.and(current, ops[index])
        }
        return current.build()
    }
}
