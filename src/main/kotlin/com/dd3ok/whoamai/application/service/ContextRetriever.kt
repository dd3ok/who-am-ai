package com.dd3ok.whoamai.application.service
	
import com.dd3ok.whoamai.application.port.out.PortfolioCatalogEntry
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ConversationTopic
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import com.dd3ok.whoamai.domain.ChatMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.stereotype.Component
import kotlin.coroutines.cancellation.CancellationException

/**
 * 작업 목적: 규칙 기반 및 벡터 검색을 통해 사용자 프롬프트에 적합한 이력서 컨텍스트를 조회한다.
 * 주요 로직: 명시적 규칙으로 우선 검색 후, 필요 시 Spring AI VectorStore 필터 표현식을 조합해 유사도 검색을 위임한다.
 */
@Component
class ContextRetriever(
    private val resumePersistencePort: ResumePersistencePort,
    private val resumeProviderPort: ResumeProviderPort,
    private val ownDomainProfileProvider: OwnDomainProfileProvider,
    @Value("\${rag.search.metadata-filter-enabled:true}") private val metadataFilterEnabled: Boolean,
    @Value("\${rag.search.similarity-threshold-override:-1}") private val similarityThresholdOverride: Double = -1.0
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val filterExpressionBuilder = FilterExpressionBuilder()

    private val rules: List<(RuleContext) -> String?> = listOf(
        { ctx -> if (RECENT_ACTIVITY_KEYWORDS.any { ctx.query.contains(it) }) ChunkIdGenerator.forRecentActivities() else null },
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
            if (matchesIntro && matchesName && !isSpecificResumeSlotQuery(ctx.query)) {
                ChunkIdGenerator.forSummary()
            } else null
        }
    )

    /** Reuse the existing source rules without reading MongoDB or treating assistant prose as evidence. */
    fun conversationTopics(userPrompt: String, history: List<ChatMessage>): List<ConversationTopic> {
        val resume = resumeProviderPort.getResume()
        val names = NameFragmentExtractor.extract(resume.name)
        fun workTopics(query: String): List<ConversationTopic> {
            val projects = resume.projects.filter { matchesProjectQuery(query, it) }
            if (projects.isNotEmpty()) return projects.map {
                ConversationTopic(it.title, listOf(ChunkIdGenerator.forProject(it.title)))
            }
            return resume.experiences.filter { exp ->
                containsResumeTerm(query, exp.company) || exp.aliases.any { containsResumeTerm(query, it) }
            }.map { exp ->
                ConversationTopic(exp.company, listOf(ChunkIdGenerator.forExperience(exp.company)) +
                    resume.projects.filter { it.company == exp.company }.map { ChunkIdGenerator.forProject(it.title) })
            }
        }
        val normalizedPrompt = normalizeResumeQuery(userPrompt)
        if (isOwnServiceQuestion(normalizedPrompt) || workTopics(userPrompt).isNotEmpty()) return emptyList()
        if (!isFollowUpQuestion(userPrompt) &&
            isLikelyOwnDomainQuestion(userPrompt, normalizedPrompt, resume, names) &&
            rules.any { it(RuleContext(normalizedPrompt, names)) != null }
        ) {
            return emptyList()
        }
        return history.filter { it.role == "user" }.flatMap { message ->
            val normalized = normalizeResumeQuery(message.text)
            when {
                isOwnServiceQuestion(normalized) -> listOf(ConversationTopic("who-am-ai 구현 정보", listOf("service_profile")))
                !isLikelyOwnDomainQuestion(message.text, normalized, resume, names) -> emptyList()
                else -> workTopics(message.text).ifEmpty {
                    rules.mapNotNull { it(RuleContext(normalized, names)) }.map { id ->
                        when (id) {
                            "projects" -> ConversationTopic("프로젝트", resume.projects.map { ChunkIdGenerator.forProject(it.title) })
                            "experiences" -> ConversationTopic("경력", resume.experiences.map { ChunkIdGenerator.forExperience(it.company) })
                            else -> ConversationTopic(withSourceDetails(ResumeSearchResult(id, inferChunkType(id), "")).title, listOf(id))
                        }
                    }
                }
            }
        }.filter { it.sourceIds.isNotEmpty() }.distinct()
    }

    suspend fun retrieveSourcesForTopic(topic: ConversationTopic): List<ResumeSearchResult> =
        retrieveSourcesByIds(topic.sourceIds)

    /**
     * Catalog selector가 선택한 chunk ID 목록을 순서대로 조회해 출처 메타데이터를 채운다.
     */
    suspend fun retrieveSourcesByIds(chunkIds: List<String>): List<ResumeSearchResult> {
        val distinctIds = chunkIds.distinct()
        if (distinctIds.isEmpty()) return emptyList()
        val stored = fetchStructuredChunks(distinctIds.filterNot { it == "service_profile" })
            .associateBy { it.chunkId }
        return distinctIds.mapNotNull { chunkId ->
            if (chunkId == "service_profile") serviceProfileSource()
            else stored[chunkId]?.let(::withSourceDetails)
        }
    }

    /**
     * Tier 2 unified catalog selector에 전달할 컴팩트 포트폴리오 카탈로그를 생성한다.
     * 본문 핵심 키워드(예: GeoIP, Debezium, yfinance)를 함께 추출해 세부 기술 질문도 매칭할 수 있게 한다.
     */
    fun portfolioCatalog(): List<PortfolioCatalogEntry> {
        val resume = resumeProviderPort.getResume()
        val entries = mutableListOf<PortfolioCatalogEntry>()

        if (resume.summary.isNotBlank()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forSummary(),
                chunkType = "summary",
                title = "프로필 소개",
                summary = compactCatalogText(listOf(resume.name, resume.summary)),
                keywords = salientKeywords(emptyList(), listOf(resume.summary))
            )
        }
        if (resume.recentActivities.isNotBlank()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forRecentActivities(),
                chunkType = "recent_activities",
                title = "최근 활동",
                summary = compactCatalogText(listOf(resume.recentActivities)),
                keywords = salientKeywords(listOf("최근 활동", "근황", "공부"), listOf(resume.recentActivities))
            )
        }
        if (resume.experiences.isNotEmpty()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forTotalExperience(),
                chunkType = "experience_total",
                title = "총 경력",
                summary = compactCatalogText(resume.experiences.map { "${it.company} (${it.period.start}~${it.period.end.ifBlank { "재직중" }})" }),
                keywords = listOf("총 경력", "경력 연차", "회사 목록")
            )
        }
        if (resume.skills.isNotEmpty()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forSkills(),
                chunkType = "skills",
                title = "기술 스택",
                summary = compactCatalogText(resume.skills),
                keywords = resume.skills.take(CATALOG_KEYWORD_LIMIT)
            )
        }
        if (resume.education.isNotEmpty()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forEducation(),
                chunkType = "education",
                title = "학력",
                summary = compactCatalogText(resume.education.map { "${it.school} ${it.major} ${it.degree}" }),
                keywords = resume.education.flatMap { listOf(it.school, it.major) }.filter { it.isNotBlank() }.take(CATALOG_KEYWORD_LIMIT)
            )
        }
        if (resume.certificates.isNotEmpty()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forCertificates(),
                chunkType = "certificates",
                title = "자격증",
                summary = compactCatalogText(resume.certificates.map { it.title }),
                keywords = resume.certificates.map { it.title }.filter { it.isNotBlank() }.take(CATALOG_KEYWORD_LIMIT)
            )
        }
        if (resume.interests.isNotEmpty()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forInterests(),
                chunkType = "interests",
                title = "관심사",
                summary = compactCatalogText(resume.interests),
                keywords = resume.interests.take(CATALOG_KEYWORD_LIMIT)
            )
        }
        if (resume.mbti.isNotBlank()) {
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forMbti(),
                chunkType = "mbti",
                title = "성격",
                summary = compactCatalogText(listOf(resume.mbti)),
                keywords = listOf("MBTI", resume.mbti)
            )
        }
        if (resume.hobbies.isNotEmpty()) {
            val items = resume.hobbies.flatMap { it.items }
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forHobbies(),
                chunkType = "hobbies",
                title = "취미",
                summary = compactCatalogText(items),
                keywords = items.take(CATALOG_KEYWORD_LIMIT)
            )
        }
        val serviceProfile = ownDomainProfileProvider.serviceProfile()
        entries += PortfolioCatalogEntry(
            chunkId = "service_profile",
            chunkType = "service",
            title = "who-am-ai 구현 정보",
            summary = compactCatalogText(listOf(serviceProfile)),
            keywords = salientKeywords(listOf("who-am-ai", "RAG", "WebSocket", "Prompt"), listOf(serviceProfile))
        )

        resume.experiences.forEach { exp ->
            val periodEnd = exp.period.end.ifBlank { "재직중" }
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forExperience(exp.company),
                chunkType = "experience",
                title = "${exp.company} 경력",
                summary = compactCatalogText(listOf(exp.position, "${exp.period.start}~$periodEnd")),
                keywords = (exp.aliases + exp.tags).map(String::trim).filter(String::isNotBlank).distinct().take(CATALOG_KEYWORD_LIMIT)
            )
        }

        resume.projects.forEach { proj ->
            val bodyParts = listOf(proj.description, proj.problem, proj.contribution, proj.outcome)
            entries += PortfolioCatalogEntry(
                chunkId = ChunkIdGenerator.forProject(proj.title),
                chunkType = "project",
                title = proj.title,
                summary = compactCatalogText(listOf(proj.company) + bodyParts),
                keywords = salientKeywords(proj.aliases + proj.tags + proj.skills, bodyParts)
            )
        }
        return entries
    }

    private fun compactCatalogText(parts: List<String>, maxLength: Int = CATALOG_SUMMARY_MAX_LENGTH): String {
        val joined = parts.map(String::trim).filter(String::isNotBlank).joinToString(" / ")
            .replace(Regex("\\s+"), " ")
        return if (joined.length <= maxLength) joined else joined.take(maxLength - 3).trimEnd() + "..."
    }

    private fun salientKeywords(base: List<String>, texts: List<String>, limit: Int = CATALOG_KEYWORD_LIMIT): List<String> {
        val tokens = TECHNICAL_TOKEN_REGEX.findAll(texts.joinToString(" "))
            .map { it.value.trimEnd('.', ',', ':', ';', ')') }
            .filter { it.length >= 2 }
        return (base.asSequence() + tokens)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(limit)
            .toList()
    }

    private fun serviceProfileSource() = ResumeSearchResult("service_profile", "service", ownDomainProfileProvider.serviceProfile(),
        "who-am-ai 구현 정보", "https://github.com/dd3ok/who-am-ai")

    /**
     * 규칙 기반으로 컨텍스트를 검색합니다.
     * @return 컨텍스트를 찾으면 List<String>, 못 찾으면 빈 List를 반환합니다.
     */
    suspend fun retrieveByRule(userPrompt: String): List<String> = retrieveSourcesByRule(userPrompt).map { it.content }

    suspend fun retrieveSourcesByRule(userPrompt: String): List<ResumeSearchResult> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = normalizeResumeQuery(userPrompt)
        if (isOwnServiceQuestion(normalizedQuery)) {
            logger.info("Context retrieved by: Own Service Rule.")
            return listOf(serviceProfileSource())
        }
        val nameFragments = NameFragmentExtractor.extract(resume.name)
        if (!isLikelyOwnDomainQuestion(userPrompt, normalizedQuery, resume, nameFragments)) {
            logger.info("Prompt is not an owned-domain question. Skipping rule-based RAG.")
            return emptyList()
        }

        // 1. Specific project title/alias check
        val matchedProjects = resume.projects.filter { matchesProjectQuery(userPrompt, it, normalizedQuery) }
        val chunkIds = matchedProjects.map { ChunkIdGenerator.forProject(it.title) }.toMutableList()

        val shouldDeferBroadRule = shouldDeferBroadRuleToVector(userPrompt, resume)
        val matchedCompanies = resume.experiences.filter { exp ->
            containsResumeTerm(userPrompt, exp.company) || exp.aliases.any { containsResumeTerm(userPrompt, it) }
        }
        val matchedSkills = resume.skills.filter { containsResumeTerm(userPrompt, it) }

        // 2. General rule-based check
        val ctx = RuleContext(normalizedQuery, nameFragments)
        val matchedRules = rules.mapNotNull { it(ctx) }
        val compoundSlots = matchedRules.count { it != ChunkIdGenerator.forSummary() } > 1
        for (result in matchedRules) {
            if (((shouldDeferBroadRule && !compoundSlots) || matchedProjects.isNotEmpty()) && result in BROAD_RULE_RESULTS) {
                logger.info("Broad rule '{}' deferred to vector path because explicit entity hints exist.", result)
                continue
            }
            logger.info("Context retrieved by: General Rule ('$result').")
            chunkIds += when (result) {
                "projects" -> resume.projects.filter { project ->
                    (matchedCompanies.isEmpty() || matchedCompanies.any { it.company == project.company }) &&
                        (matchedSkills.isEmpty() || project.skills.any { it in matchedSkills })
                }.map { ChunkIdGenerator.forProject(it.title) }
                "experiences" -> if (ChunkIdGenerator.forTotalExperience() in chunkIds) emptyList()
                    else matchedCompanies.ifEmpty { resume.experiences }.map { ChunkIdGenerator.forExperience(it.company) }
                ChunkIdGenerator.forSummary() -> if (chunkIds.isEmpty()) listOf(result) else emptyList()
                else -> listOf(result)
            }
        }

        return fetchStructuredChunks(chunkIds.distinct()).map(::withSourceDetails)
    }

    /**
     * LLM 라우터의 힌트를 바탕으로 벡터 검색을 수행합니다.
     */
    suspend fun retrieveByVector(userPrompt: String, routeDecision: RouteDecision): List<String> =
        retrieveSourcesByVector(userPrompt, routeDecision).map { it.content }

    suspend fun retrieveSourcesByVector(userPrompt: String, routeDecision: RouteDecision): List<ResumeSearchResult> {
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

        val retrieved = searchSimilarSectionsSafely(
            query = finalQuery,
            topK = retrievalProfile.topK,
            filter = finalFilter,
            similarityThreshold = similarityThresholdOverride.takeIf { it in 0.0..1.0 } ?: retrievalProfile.similarityThreshold
        )
        val blended = (precise + retrieved).distinctBy { it.chunkId }
        val rerankLimit = if (routeDecision.company != null && routeDecision.project == null) {
            maxOf(8, precise.size).coerceAtMost(12)
        } else {
            4
        }
        return rerankByHeuristic(finalQuery, blended, routeDecision, rerankLimit)
            .map(::withSourceDetails)
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
        val contents = findContentsByIdsSafely(chunkIds)
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

    private fun withSourceDetails(result: ResumeSearchResult): ResumeSearchResult {
        val resume = resumeProviderPort.getResume()
        val project = resume.projects.firstOrNull { ChunkIdGenerator.forProject(it.title) == result.chunkId }
        val experience = resume.experiences.firstOrNull { ChunkIdGenerator.forExperience(it.company) == result.chunkId }
        val title = project?.title ?: experience?.let { "${it.company} 경력" } ?: when (result.chunkId) {
            "summary" -> "프로필 소개"
            "skills" -> "기술 스택"
            "education" -> "학력"
            "certificates" -> "자격증"
            "recent_activities" -> "최근 활동"
            "experience_total_summary" -> "총 경력"
            "interests" -> "관심사"
            "hobbies" -> "취미"
            "mbti" -> "성격"
            else -> result.title
        }
        val url = project?.sourceUrl?.takeIf { value ->
            runCatching { java.net.URI(value) }.getOrNull()?.let {
                it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
            } == true
        }
        return result.copy(title = title, url = url)
    }

    private suspend fun findContentsByIdsSafely(chunkIds: List<String>): Map<String, String> {
        return try {
            resumePersistencePort.findContentsByIds(chunkIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Resume chunk lookup failed. Continuing without rule-based RAG context.", e)
            emptyMap()
        }
    }

    private suspend fun searchSimilarSectionsSafely(
        query: String,
        topK: Int,
        filter: Filter.Expression?,
        similarityThreshold: Double?
    ): List<ResumeSearchResult> {
        return try {
            resumePersistencePort.searchSimilarSections(
                query = query,
                topK = topK,
                filter = filter,
                similarityThreshold = similarityThreshold
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Resume vector search failed. Continuing without vector RAG context.", e)
            emptyList()
        }
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
        if (RECENT_ACTIVITY_KEYWORDS.any { normalizedQuery.contains(it) }) {
            prefer("recent_activity", 16)
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
            RECENT_ACTIVITY_KEYWORDS.any { normalizedQuery.contains(it) } ||
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
        query: String,
        resume: com.dd3ok.whoamai.domain.Resume
    ): Boolean {
        val normalizedQuery = normalizeResumeQuery(query)
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
        val hasSkillHint = resume.skills.any { containsResumeTerm(query, it) }
        val hasProjectHint = resume.projects.any { project ->
            matchesProjectQuery(query, project, normalizedQuery)
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

private val INTRO_KEYWORDS = normalizedKeywords("누구야", "누구세요", "소개", "자기소개", "소개해줘", "에 대해 알려")
private val RECENT_ACTIVITY_KEYWORDS = ResumeIntentKeywords.recentActivity
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
private const val CATALOG_SUMMARY_MAX_LENGTH = 260
private const val CATALOG_KEYWORD_LIMIT = 40
private val TECHNICAL_TOKEN_REGEX = Regex("[A-Za-z][A-Za-z0-9_.+-]{1,}")

private fun isSpecificResumeSlotQuery(normalizedQuery: String): Boolean {
    val slotKeywords = TOTAL_EXPERIENCE_KEYWORDS +
        PROJECT_KEYWORDS +
        EXPERIENCE_KEYWORDS +
        RECENT_ACTIVITY_KEYWORDS +
        CERTIFICATE_KEYWORDS +
        INTEREST_KEYWORDS +
        SKILL_KEYWORDS +
        EDU_KEYWORDS +
        PERSONALITY_KEYWORDS +
        HOBBY_KEYWORDS
    return slotKeywords.any { normalizedQuery.contains(it) }
}
