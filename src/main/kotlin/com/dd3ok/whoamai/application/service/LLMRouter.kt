package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import com.dd3ok.whoamai.domain.ChatMessage
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LLMRouter(
    private val resumeProviderPort: ResumeProviderPort,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun route(userPrompt: String, history: List<ChatMessage> = emptyList()): RouteDecision {
        val resume = resumeProviderPort.getResume()
        val resolvedPrompt = resolveQuery(userPrompt, history)
        val normalized = normalizeResumeQuery(resolvedPrompt)
        val nameFragments = NameFragmentExtractor.extract(resume.name)

        if (isOwnServiceQuestion(normalized)) {
            logger.info("Routing resolved by own-service intent.")
            return recordDecision(
                RouteDecision(
                    queryType = QueryType.RESUME_RAG,
                    keywords = listOf("who-am-ai", "service")
                ),
                "own_service"
            )
        }

        if (!isLikelyOwnDomainQuestion(resolvedPrompt, normalized, resume, nameFragments)) {
            logger.info("Routing defaulted to NON_RAG because prompt is not an owned-domain question.")
            return recordDecision(RouteDecision(QueryType.NON_RAG), "question_guard")
        }

        val heuristicDecision = buildHeuristicDecision(resolvedPrompt, normalized, resume)
        if (heuristicDecision != null) {
            logger.info("Routing resolved by heuristic path: {}", heuristicDecision)
            return recordDecision(heuristicDecision, "heuristic")
        }

        val decision = RouteDecision(
            queryType = QueryType.RESUME_RAG,
            company = null,
            project = null,
            skills = null,
            keywords = extractKeywords(resolvedPrompt, emptyList(), emptyList()).ifEmpty { null }
        )
        return recordDecision(decision, "intent_only")
    }

    /** Resolve short follow-ups before either rule retrieval or routing. Only user turns establish a topic. */
    fun resolveQuery(userPrompt: String, history: List<ChatMessage>): String {
        val normalized = normalizeResumeQuery(userPrompt)
        // A substantial excerpt of a recent assistant answer is a reference to verify,
        // never evidence of a new visitor experience or a new portfolio fact.
        val quotedAnswer = normalized.length >= 24 && history.takeLast(12).any {
            it.role in setOf("model", "assistant") && normalizeResumeQuery(it.text).contains(normalized)
        }
        if (!quotedAnswer && !isFollowUpQuestion(userPrompt)) return userPrompt
        val resume = resumeProviderPort.getResume()
        val names = NameFragmentExtractor.extract(resume.name)
        if ((!quotedAnswer && hasExternalNamedSubject(userPrompt, names)) ||
            NameFragmentExtractor.matches(normalized, names) ||
            explicitTopics(normalized, resume).isNotEmpty()
        ) return userPrompt

        for (message in history.asReversed().filter { it.role == "user" }.take(6)) {
            val previous = normalizeResumeQuery(message.text)
            val topics = explicitTopics(previous, resume)
            if (topics.isEmpty() && isFollowUpQuestion(message.text) && !hasExternalNamedSubject(message.text, names)) continue
            // A topic switch ends the chain; never resurrect an older company from unrelated conversation.
            if (!isLikelyOwnDomainQuestion(message.text, previous, resume, names)) return userPrompt
            val target = topics.singleOrNull() ?: resume.name
            return "$target — $userPrompt"
        }
        return userPrompt
    }

    /** Only user-established, known portfolio entities can be selected by semantic resolution. */
    fun followUpTopics(userPrompt: String, history: List<ChatMessage>): List<String> {
        val resume = resumeProviderPort.getResume()
        val names = NameFragmentExtractor.extract(resume.name)
        val current = normalizeResumeQuery(userPrompt)
        if (NameFragmentExtractor.matches(current, names) || explicitTopics(current, resume).isNotEmpty() ||
            isOwnServiceQuestion(current)) return emptyList()
        return history.filter { it.role == "user" }.flatMap { message ->
            val normalized = normalizeResumeQuery(message.text)
            val topics = explicitTopics(normalized, resume)
            when {
                topics.isNotEmpty() -> topics
                !isFollowUpQuestion(message.text) && isLikelyOwnDomainQuestion(message.text, normalized, resume, names) ->
                    listOf(resume.name)
                else -> emptyList()
            }
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun explicitTopics(query: String, resume: com.dd3ok.whoamai.domain.Resume): List<String> {
        val projects = resume.projects.filter { it.title.isNotBlank() && query.contains(normalizeResumeQuery(it.title)) }
        if (projects.isNotEmpty()) return projects.map { it.title }.distinct()
        return resume.experiences.filter { exp ->
            exp.company.isNotBlank() && (query.contains(normalizeResumeQuery(exp.company)) ||
                exp.aliases.any { it.isNotBlank() && query.contains(normalizeResumeQuery(it)) })
        }.map { it.company }.distinct()
    }

    companion object {
        private val SLOT_KEYWORDS = listOf(
            "경력", "직무", "회사", "프로젝트", "스킬", "기술", "자격증", "cert",
            "학력", "학교", "전공", "mbti", "관심", "취미", "포트폴리오", "이력서",
            "역할", "책임", "성과", "강점", "경험", "리딩", "협업", "커뮤니케이션",
            "트러블슈팅", "성능", "장애", "개선", "최적화", "아키텍처", "msa", "ddd",
            "자동화", "배치", "테스트", "품질", "보안", "인증", "인가", "학습"
        ).map(::normalizeResumeQuery) + ResumeIntentKeywords.recentActivity

        private val STOPWORDS = setOf(
            "알려줘", "말해줘", "설명", "설명해줘", "어떻게", "무엇", "뭐", "대한", "관련", "정리", "좀", "주세요"
        ).map { it.lowercase() }.toSet()
    }

    private fun containsResumeSlots(userPrompt: String, resume: com.dd3ok.whoamai.domain.Resume): Boolean {
        val normalizedPrompt = normalizeResumeQuery(userPrompt)
        if (SLOT_KEYWORDS.any { normalizedPrompt.contains(it) }) return true

        val companyHit = resume.experiences.any { exp ->
            normalizedPrompt.contains(normalizeResumeQuery(exp.company)) ||
                exp.aliases.any { alias -> normalizedPrompt.contains(normalizeResumeQuery(alias)) }
        }
        if (companyHit) return true

        val projectHit = resume.projects.any { proj ->
            normalizedPrompt.contains(normalizeResumeQuery(proj.title)) ||
                proj.skills.any { skill -> containsResumeTerm(userPrompt, skill) } ||
                proj.tags.any { tag -> containsResumeTerm(userPrompt, tag) }
        }
        if (projectHit) return true

        val skillHit = resume.skills.any { containsResumeTerm(userPrompt, it) }
        if (skillHit) return true

        val educationHit = resume.education.any { edu ->
            normalizedPrompt.contains(normalizeResumeQuery(edu.school)) ||
                normalizedPrompt.contains(normalizeResumeQuery(edu.major))
        }
        return educationHit
    }

    private fun buildHeuristicDecision(
        userPrompt: String,
        normalizedPrompt: String,
        resume: com.dd3ok.whoamai.domain.Resume
    ): RouteDecision? {
        val matchedCompanies = resume.experiences
            .filter { exp ->
                normalizedPrompt.contains(normalizeResumeQuery(exp.company)) ||
                    exp.aliases.any { alias -> normalizedPrompt.contains(normalizeResumeQuery(alias)) }
            }
            .map { it.company }
            .distinct()

        val matchedProjects = resume.projects
            .filter { project -> normalizedPrompt.contains(normalizeResumeQuery(project.title)) }
            .map { it.title }
            .distinct()

        val matchedSkills = resume.skills
            .filter { containsResumeTerm(userPrompt, it) }
            .distinct()

        val hasResumeSignal = containsResumeSlots(userPrompt, resume)
        if (!hasResumeSignal && matchedCompanies.isEmpty() && matchedProjects.isEmpty() && matchedSkills.isEmpty()) {
            return null
        }

        val company = matchedCompanies.firstOrNull()
        val project = matchedProjects.firstOrNull()
        val skills = matchedSkills.ifEmpty { null }
        val extractedKeywords = mergeKeywords(
            extractKeywords(userPrompt, matchedCompanies, matchedSkills),
            company,
            project
        )
        return RouteDecision(
            queryType = QueryType.RESUME_RAG,
            company = company,
            project = project,
            skills = skills,
            keywords = extractedKeywords.ifEmpty { null }
        )
    }

    private fun mergeKeywords(
        extractedKeywords: List<String>,
        company: String?,
        project: String?
    ): List<String> {
        return (listOfNotNull(company, project) + extractedKeywords)
            .distinct()
            .take(5)
    }

    private fun extractKeywords(
        userPrompt: String,
        matchedCompanies: List<String>,
        matchedSkills: List<String>
    ): List<String> {
        val normalizedMatched = (matchedCompanies + matchedSkills).map(::normalizeResumeQuery).toSet()
        val candidates = userPrompt.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { normalizedMatched.contains(normalizeResumeQuery(it)) }
            .filterNot { STOPWORDS.contains(it) }
            .distinct()
        return candidates.take(5)
    }

    private fun recordDecision(decision: RouteDecision, source: String): RouteDecision {
        meterRegistry.counter(
            "whoamai.router.decision.total",
            "source", source,
            "query_type", decision.queryType.name
        ).increment()
        return decision
    }

}
