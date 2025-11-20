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

    private val rules: List<(RuleContext) -> String?> by lazy {
        listOf(
            { ctx ->
                val matchesIntro = INTRO_KEYWORDS.any { ctx.query.contains(it) }
                if (matchesIntro || NameFragmentExtractor.matches(ctx.query, ctx.nameFragments)) {
                    ChunkIdGenerator.forSummary()
                } else null
            },
            { ctx -> if (TOTAL_EXPERIENCE_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forTotalExperience() else null },
            { ctx -> if (PROJECT_KEYWORDS.any { ctx.query.contains(it) }) "projects" else null },
            { ctx -> if (EXPERIENCE_KEYWORDS.any { ctx.query.contains(it) }) "experiences" else null },
            { ctx -> if (CERTIFICATE_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forCertificates() else null },
            { ctx -> if (INTEREST_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forInterests() else null },
            { ctx -> if (SKILL_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forSkills() else null },
            { ctx -> if (EDU_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forEducation() else null },
            { ctx -> if (PERSONALITY_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forMbti() else null },
            { ctx -> if (HOBBY_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forHobbies() else null }
        )
    }

    /**
     * 규칙 기반으로 컨텍스트를 검색합니다.
     * @return 컨텍스트를 찾으면 List<String>, 못 찾으면 빈 List를 반환합니다.
     */
    suspend fun retrieveByRule(userPrompt: String): List<String> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()
        if (isSelfIdentityPrompt(normalizedQuery)) {
            logger.info("Self-identity prompt detected. Skipping rule-based RAG.")
            return emptyList()
        }
        val nameFragments = NameFragmentExtractor.extract(resume.name)

        // 1. Specific project title check
        val matchedProject = resume.projects.find { userPrompt.contains(it.title) }
        if (matchedProject != null) {
            logger.info("Context retrieved by: Specific Project Rule ('${matchedProject.title}').")
            val projectId = ChunkIdGenerator.forProject(matchedProject.title)
            return resumePersistencePort.findContentById(projectId)?.let { listOf(it) } ?: emptyList()
        }

        // 2. General rule-based check
        val ctx = RuleContext(normalizedQuery, nameFragments)
        for (rule in rules) {
            val result = rule(ctx)
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

        return emptyList() // 규칙에 맞는 것이 없으면 빈 리스트 반환
    }

    /**
     * LLM 라우터의 힌트를 바탕으로 벡터 검색을 수행합니다.
     */
    suspend fun retrieveByVector(userPrompt: String, routeDecision: RouteDecision): List<String> {
        logger.info("No rules matched. Context retrieved by: Vector Search (as per LLMRouter).")
        val filterOps = mutableListOf<FilterExpressionBuilder.Op>()
        routeDecision.company?.let {
            filterOps.add(filterExpressionBuilder.eq("company", it))
        }
        routeDecision.skills?.takeIf { it.isNotEmpty() }?.let { skills ->
            filterOps.add(filterExpressionBuilder.`in`("skills", skills))
        }

        val finalFilter = if (filterOps.isEmpty()) {
            null
        } else {
            filterOps.reduce { acc, op -> filterExpressionBuilder.and(acc, op) }.build()
        }

        val searchKeywords = routeDecision.keywords?.joinToString(" ") ?: ""
        val finalQuery = "$userPrompt $searchKeywords".trim()

        return resumePersistencePort.searchSimilarSections(finalQuery, topK = 3, filter = finalFilter)
    }
}

private data class RuleContext(
    val query: String,
    val nameFragments: Set<String>
)

private fun normalizedKeywords(vararg keywords: String): List<String> =
    keywords.map { it.replace(Regex("\\s+"), "").lowercase() }

private val INTRO_KEYWORDS = normalizedKeywords("누구야", "누구세요", "소개", "자기소개", "소개해줘")
private val TOTAL_EXPERIENCE_KEYWORDS = normalizedKeywords("총 경력", "총경력", "전체경력")
private val PROJECT_KEYWORDS = normalizedKeywords("프로젝트", "project")
private val EXPERIENCE_KEYWORDS = normalizedKeywords("경력", "이력", "회사")
private val CERTIFICATE_KEYWORDS = normalizedKeywords("자격증", "certificate")
private val INTEREST_KEYWORDS = normalizedKeywords("관심사", "관심있는", "관심있는게", "관심", "흥미", "좋아하는", "관심분야", "관심있는분야")
private val SKILL_KEYWORDS = normalizedKeywords("기술", "스킬", "스택")
private val EDU_KEYWORDS = normalizedKeywords("학력", "학교", "대학")
private val PERSONALITY_KEYWORDS = normalizedKeywords("mbti", "성격")
private val HOBBY_KEYWORDS = normalizedKeywords("취미", "여가시간")

private fun isSelfIdentityPrompt(normalizedQuery: String): Boolean {
    val identityTokens = listOf("너는", "넌", "누구야", "누구니", "너뭐", "너무엇", "뭐로만들", "만들어졌")
    return identityTokens.any { normalizedQuery.contains(it) }
}
