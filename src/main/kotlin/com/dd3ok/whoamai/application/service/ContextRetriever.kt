package com.dd3ok.whoamai.application.service
	
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.stereotype.Component

/**
 * 작업 목적: 규칙 기반 및 벡터 검색을 통해 사용자 프롬프트에 적합한 이력서 컨텍스트를 조회한다.
 * 주요 로직: 명시적 규칙으로 우선 검색 후, 필요 시 Spring AI VectorStore 필터 표현식을 조합해 유사도 검색을 위임한다.
 */
@Component
class ContextRetriever(
    private val resumePersistencePort: ResumePersistencePort,
    private val resumeProviderPort: ResumeProviderPort,
    private val ownDomainProfileProvider: OwnDomainProfileProvider,
    @Value("\${rag.search.metadata-filter-enabled:true}") private val metadataFilterEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val filterExpressionBuilder = FilterExpressionBuilder()

    private val rules: List<(RuleContext) -> String?> by lazy {
        listOf(
            { ctx -> if (TOTAL_EXPERIENCE_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forTotalExperience() else null },
            { ctx -> if (PROJECT_KEYWORDS.any { ctx.query.contains(it) }) "projects" else null },
            { ctx -> if (EXPERIENCE_KEYWORDS.any { ctx.query.contains(it) }) "experiences" else null },
            { ctx -> if (CERTIFICATE_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forCertificates() else null },
            { ctx -> if (INTEREST_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forInterests() else null },
            { ctx -> if (SKILL_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forSkills() else null },
            { ctx -> if (EDU_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forEducation() else null },
            { ctx -> if (PERSONALITY_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forMbti() else null },
            { ctx -> if (HOBBY_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forHobbies() else null },
            { ctx ->
                val matchesIntro = INTRO_KEYWORDS.any { ctx.query.contains(it) }
                val matchesName = NameFragmentExtractor.matches(ctx.query, ctx.nameFragments)
                if (matchesIntro || (matchesName && !isSpecificResumeSlotQuery(ctx.query))) {
                    ChunkIdGenerator.forSummary()
                } else null
            }
        )
    }

    /**
     * 규칙 기반으로 컨텍스트를 검색합니다.
     * @return 컨텍스트를 찾으면 List<String>, 못 찾으면 빈 List를 반환합니다.
     */
    suspend fun retrieveByRule(userPrompt: String): List<String> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = normalizeResumeQuery(userPrompt)
        if (isOwnServiceQuestion(normalizedQuery)) {
            logger.info("Context retrieved by: Own Service Rule.")
            return listOf(ownDomainProfileProvider.serviceProfile())
        }
        val nameFragments = NameFragmentExtractor.extract(resume.name)
        if (!isLikelyOwnDomainQuestion(userPrompt, normalizedQuery, resume, nameFragments)) {
            logger.info("Prompt is not an owned-domain question. Skipping rule-based RAG.")
            return emptyList()
        }

        // 1. Specific project title check
        val matchedProject = resume.projects.find { normalizedQuery.contains(normalizeResumeQuery(it.title)) }
        if (matchedProject != null) {
            logger.info("Context retrieved by: Specific Project Rule ('${matchedProject.title}').")
            val projectId = ChunkIdGenerator.forProject(matchedProject.title)
            return fetchPlainChunks(listOf(projectId))
        }

        val shouldDeferBroadRule = shouldDeferBroadRuleToVector(normalizedQuery, resume)

        // 2. General rule-based check
        val ctx = RuleContext(normalizedQuery, nameFragments)
        for (rule in rules) {
            val result = rule(ctx)
            if (result != null) {
                if (shouldDeferBroadRule && result in BROAD_RULE_RESULTS) {
                    logger.info("Broad rule '{}' deferred to vector path because explicit entity hints exist.", result)
                    continue
                }
                logger.info("Context retrieved by: General Rule ('$result').")
                return when (result) {
                    "projects" -> fetchPlainChunks(
                        resume.projects.map { ChunkIdGenerator.forProject(it.title) }
                    )
                    "experiences" -> fetchPlainChunks(
                        resume.experiences.map { ChunkIdGenerator.forExperience(it.company) }
                    )
                    else -> fetchPlainChunks(listOf(result))
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
        val precise = retrievePreciseByRouteHints(routeDecision)
        val retrievalProfile = resolveRetrievalProfile(userPrompt, routeDecision)

        val filterOps = mutableListOf<FilterExpressionBuilder.Op>()
        if (shouldApplyMetadataFilter(routeDecision)) {
            routeDecision.company?.let {
                filterOps.add(filterExpressionBuilder.eq("company", it))
            }
            routeDecision.skills?.takeIf { it.isNotEmpty() }?.let { skills ->
                filterOps.add(filterExpressionBuilder.`in`("skills", skills))
            }
        }

        val finalFilter = if (filterOps.isEmpty()) {
            null
        } else {
            filterOps.reduce { acc, op -> filterExpressionBuilder.and(acc, op) }.build()
        }

        val searchKeywords = routeDecision.keywords?.joinToString(" ") ?: ""
        val finalQuery = "$userPrompt $searchKeywords".trim()

        val retrieved = resumePersistencePort.searchSimilarSections(
            query = finalQuery,
            topK = retrievalProfile.topK,
            filter = finalFilter,
            similarityThreshold = retrievalProfile.similarityThreshold
        )
        val blended = (precise + retrieved).distinctBy { it.chunkId }
        return rerankByHeuristic(finalQuery, blended, routeDecision, 4)
            .map { it.content }
    }

    /** 질의 토큰 교집합 + chunk type 가중치 기반 리랭크로 가장 관련도 높은 컨텍스트를 우선 선택한다. */
    private fun rerankByHeuristic(
        query: String,
        contents: List<ResumeSearchResult>,
        routeDecision: RouteDecision,
        limit: Int
    ): List<ResumeSearchResult> {
        if (contents.isEmpty()) return contents
        val tokens = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && !RERANK_STOPWORDS.contains(it) }
            .toSet()
        if (tokens.isEmpty()) return contents.take(limit)

        val preferredTypes = preferredChunkTypes(query, routeDecision)

        return contents
            .map { result ->
                val lowered = result.content.lowercase()
                val matchCount = tokens.count { lowered.contains(it) }
                val typeWeight = preferredTypes[result.chunkType] ?: 0
                val companyWeight = if (
                    routeDecision.company != null &&
                    lowered.contains(routeDecision.company.lowercase())
                ) 2 else 0
                result to (matchCount * 10 + typeWeight + companyWeight)
            }
            .sortedWith(
                compareByDescending<Pair<ResumeSearchResult, Int>> { it.second }
                    .thenByDescending { it.first.content.length }
            )
            .map { it.first }
            .take(limit)
    }

    private suspend fun retrievePreciseByRouteHints(routeDecision: RouteDecision): List<ResumeSearchResult> {
        val resume = resumeProviderPort.getResume()
        val preciseChunkIds = mutableListOf<String>()

        routeDecision.project?.let { projectTitle ->
            resume.projects.firstOrNull {
                normalizeResumeQuery(it.title) == normalizeResumeQuery(projectTitle)
            }?.let { matchedProject ->
                preciseChunkIds += ChunkIdGenerator.forProject(matchedProject.title)
                preciseChunkIds += ChunkIdGenerator.forExperience(matchedProject.company)
            }
        }

        routeDecision.company?.let { company ->
            val matchedExperience = resume.experiences.firstOrNull {
                normalizeResumeQuery(it.company) == normalizeResumeQuery(company) ||
                    it.aliases.any { alias -> normalizeResumeQuery(alias) == normalizeResumeQuery(company) }
            }
            matchedExperience?.let {
                preciseChunkIds += ChunkIdGenerator.forExperience(it.company)
                resume.projects
                    .filter { project -> normalizeResumeQuery(project.company) == normalizeResumeQuery(it.company) }
                    .forEach { project -> preciseChunkIds += ChunkIdGenerator.forProject(project.title) }
            }
        }

        val normalizedSkillHints = routeDecision.skills.orEmpty().map(::normalizeResumeQuery).toSet()
        if (normalizedSkillHints.isNotEmpty()) {
            preciseChunkIds += ChunkIdGenerator.forSkills()
            resume.projects
                .filter { project ->
                    project.skills.any { skill -> normalizedSkillHints.contains(normalizeResumeQuery(skill)) } ||
                        project.tags.any { tag -> normalizedSkillHints.contains(normalizeResumeQuery(tag)) }
                }
                .forEach { project -> preciseChunkIds += ChunkIdGenerator.forProject(project.title) }
        }

        return preciseChunkIds
            .distinct()
            .let { fetchStructuredChunks(it) }
    }

    private suspend fun fetchStructuredChunks(chunkIds: List<String>): List<ResumeSearchResult> {
        if (chunkIds.isEmpty()) return emptyList()
        val contents = resumePersistencePort.findContentsByIds(chunkIds)
        return chunkIds.mapNotNull { chunkId ->
            contents[chunkId]?.let { content ->
                ResumeSearchResult(
                    chunkId = chunkId,
                    chunkType = inferChunkType(chunkId),
                    content = content
                )
            }
        }
    }

    private suspend fun fetchPlainChunks(chunkIds: List<String>): List<String> {
        if (chunkIds.isEmpty()) return emptyList()
        val contents = resumePersistencePort.findContentsByIds(chunkIds)
        return chunkIds.mapNotNull(contents::get)
    }

    private fun preferredChunkTypes(query: String, routeDecision: RouteDecision): Map<String, Int> {
        val normalizedQuery = normalizeResumeQuery(query)
        val weights = mutableMapOf<String, Int>()

        fun prefer(type: String, weight: Int) {
            weights[type] = maxOf(weights[type] ?: 0, weight)
        }

        if (routeDecision.company != null) {
            prefer("experience", 12)
            prefer("project", 8)
        }
        if (routeDecision.project != null) {
            prefer("project", 16)
            prefer("experience", 6)
        }
        if (!routeDecision.skills.isNullOrEmpty()) {
            prefer("skills", 12)
            prefer("project", 10)
        }
        if (PROJECT_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("project", 14)
        }
        if (EXPERIENCE_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("experience", 14)
        }
        if (SKILL_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("skills", 14)
        }
        if (EDU_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("education", 16)
        }
        if (CERTIFICATE_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("certificate", 16)
        }
        if (INTEREST_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("interest", 16)
        }
        if (HOBBY_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("hobby", 16)
        }
        if (PERSONALITY_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("personality", 16)
        }
        if (TOTAL_EXPERIENCE_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("summary", 16)
        }
        if (PROJECT_ORIENTED_DETAIL_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("project", 12)
        }
        if (EXPERIENCE_ORIENTED_DETAIL_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("experience", 10)
        }

        return weights
    }

    private fun resolveRetrievalProfile(query: String, routeDecision: RouteDecision): RetrievalProfile {
        val normalizedQuery = normalizeResumeQuery(query)
        val hasExplicitHint = routeDecision.company != null || routeDecision.project != null || !routeDecision.skills.isNullOrEmpty()
        if (hasExplicitHint) {
            return RetrievalProfile(topK = 4, similarityThreshold = 0.72)
        }
        if (isProfileFocusedQuery(normalizedQuery)) {
            return RetrievalProfile(topK = 3, similarityThreshold = 0.70)
        }
        if (isAbstractCapabilityQuery(normalizedQuery)) {
            return RetrievalProfile(topK = 8, similarityThreshold = 0.58)
        }
        return RetrievalProfile(topK = 6, similarityThreshold = 0.65)
    }

    private fun isProfileFocusedQuery(normalizedQuery: String): Boolean {
        return TOTAL_EXPERIENCE_KEYWORDS.any { normalizedQuery.contains(it) } ||
            EDU_KEYWORDS.any { normalizedQuery.contains(it) } ||
            CERTIFICATE_KEYWORDS.any { normalizedQuery.contains(it) } ||
            INTEREST_KEYWORDS.any { normalizedQuery.contains(it) } ||
            HOBBY_KEYWORDS.any { normalizedQuery.contains(it) } ||
            PERSONALITY_KEYWORDS.any { normalizedQuery.contains(it) }
    }

    private fun isAbstractCapabilityQuery(normalizedQuery: String): Boolean {
        return PROJECT_ORIENTED_DETAIL_KEYWORDS.any { normalizedQuery.contains(it) } ||
            EXPERIENCE_ORIENTED_DETAIL_KEYWORDS.any { normalizedQuery.contains(it) }
    }

    private fun shouldApplyMetadataFilter(routeDecision: RouteDecision): Boolean {
        if (!metadataFilterEnabled) {
            return false
        }
        return routeDecision.company != null || !routeDecision.skills.isNullOrEmpty()
    }

    private fun shouldDeferBroadRuleToVector(
        normalizedQuery: String,
        resume: com.dd3ok.whoamai.domain.Resume
    ): Boolean {
        val hasBroadRuleKeyword = PROJECT_KEYWORDS.any { normalizedQuery.contains(it) } ||
            EXPERIENCE_KEYWORDS.any { normalizedQuery.contains(it) } ||
            SKILL_KEYWORDS.any { normalizedQuery.contains(it) }

        if (!hasBroadRuleKeyword) {
            return false
        }

        val hasCompanyHint = resume.experiences.any { exp ->
            normalizedQuery.contains(normalizeResumeQuery(exp.company)) ||
                exp.aliases.any { alias -> normalizedQuery.contains(normalizeResumeQuery(alias)) }
        }
        val hasSkillHint = resume.skills.any { normalizedQuery.contains(normalizeResumeQuery(it)) }
        val hasProjectHint = resume.projects.any { project ->
            normalizedQuery.contains(normalizeResumeQuery(project.title))
        }
        return hasCompanyHint || hasSkillHint || hasProjectHint
    }

    private fun inferChunkType(chunkId: String): String = when {
        chunkId.startsWith("project_") -> "project"
        chunkId.startsWith("experience_") && chunkId != "experience_total_summary" -> "experience"
        chunkId == "experience_total_summary" -> "summary"
        else -> chunkId
    }

    private data class RetrievalProfile(
        val topK: Int,
        val similarityThreshold: Double
    )
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
private val RERANK_STOPWORDS = normalizedKeywords("알려줘", "말해줘", "설명", "설명해줘", "정리", "대한", "관련", "좀", "해주세요")
private val PROJECT_ORIENTED_DETAIL_KEYWORDS = normalizedKeywords(
    "트러블슈팅", "성능", "장애", "개선", "최적화", "아키텍처", "msa", "ddd", "자동화", "배치", "테스트", "품질", "보안", "인증", "인가"
)
private val EXPERIENCE_ORIENTED_DETAIL_KEYWORDS = normalizedKeywords(
    "역할", "책임", "성과", "강점", "경험", "리딩", "협업", "커뮤니케이션"
)
private val BROAD_RULE_RESULTS = setOf("projects", "experiences", "skills")

private fun isSpecificResumeSlotQuery(normalizedQuery: String): Boolean {
    val slotKeywords = TOTAL_EXPERIENCE_KEYWORDS +
        PROJECT_KEYWORDS +
        EXPERIENCE_KEYWORDS +
        CERTIFICATE_KEYWORDS +
        INTEREST_KEYWORDS +
        SKILL_KEYWORDS +
        EDU_KEYWORDS +
        PERSONALITY_KEYWORDS +
        HOBBY_KEYWORDS
    return slotKeywords.any { normalizedQuery.contains(it) }
}
