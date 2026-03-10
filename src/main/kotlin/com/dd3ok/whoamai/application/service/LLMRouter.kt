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
        val normalized = normalizeResumeQuery(userPrompt)
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

        if (!isLikelyOwnDomainQuestion(userPrompt, normalized, resume, nameFragments)) {
            logger.info("Routing defaulted to NON_RAG because prompt is not an owned-domain question.")
            return recordDecision(RouteDecision(QueryType.NON_RAG), "question_guard")
        }

        val heuristicDecision = buildHeuristicDecision(userPrompt, normalized, resume, history)
        if (heuristicDecision != null) {
            logger.info("Routing resolved by heuristic path: {}", heuristicDecision)
            return recordDecision(heuristicDecision, "heuristic")
        }

        val decision = RouteDecision(
            queryType = QueryType.RESUME_RAG,
            company = null,
            project = null,
            skills = null,
            keywords = extractKeywords(userPrompt, emptyList(), emptyList()).ifEmpty { null }
        )
        return recordDecision(decision, "intent_only")
    }

    companion object {
        private val SLOT_KEYWORDS = listOf(
            "경력", "직무", "회사", "프로젝트", "스킬", "기술", "자격증", "cert",
            "학력", "학교", "전공", "mbti", "관심", "취미", "포트폴리오", "이력서",
            "역할", "책임", "성과", "강점", "경험", "리딩", "협업", "커뮤니케이션",
            "트러블슈팅", "성능", "장애", "개선", "최적화", "아키텍처", "msa", "ddd",
            "자동화", "배치", "테스트", "품질", "보안", "인증", "인가", "학습"
        ).map { it.lowercase() }

        private val STOPWORDS = setOf(
            "알려줘", "말해줘", "설명", "설명해줘", "어떻게", "무엇", "뭐", "대한", "관련", "정리", "좀", "주세요"
        ).map { it.lowercase() }.toSet()
    }

    private fun containsResumeSlots(normalizedPrompt: String, resume: com.dd3ok.whoamai.domain.Resume): Boolean {
        if (SLOT_KEYWORDS.any { normalizedPrompt.contains(it) }) return true

        val companyHit = resume.experiences.any { exp ->
            normalizedPrompt.contains(normalizeResumeQuery(exp.company)) ||
                exp.aliases.any { alias -> normalizedPrompt.contains(normalizeResumeQuery(alias)) }
        }
        if (companyHit) return true

        val projectHit = resume.projects.any { proj ->
            normalizedPrompt.contains(normalizeResumeQuery(proj.title)) ||
                proj.skills.any { skill -> normalizedPrompt.contains(normalizeResumeQuery(skill)) } ||
                proj.tags.any { tag -> normalizedPrompt.contains(normalizeResumeQuery(tag)) }
        }
        if (projectHit) return true

        val skillHit = resume.skills.any { normalizedPrompt.contains(normalizeResumeQuery(it)) }
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
        resume: com.dd3ok.whoamai.domain.Resume,
        history: List<ChatMessage>
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
            .filter { normalizedPrompt.contains(normalizeResumeQuery(it)) }
            .distinct()

        val hasResumeSignal = containsResumeSlots(normalizedPrompt, resume)
        val followUpHints = resolveRecentHints(history, resume)
        if (!hasResumeSignal && matchedCompanies.isEmpty() && matchedProjects.isEmpty() && matchedSkills.isEmpty()) {
            return null
        }

        val company = matchedCompanies.firstOrNull() ?: followUpHints?.company
        val project = matchedProjects.firstOrNull() ?: followUpHints?.project
        val skills = matchedSkills.ifEmpty { followUpHints?.skills.orEmpty() }.ifEmpty { null }
        val extractedKeywords = mergeKeywords(
            extractKeywords(userPrompt, matchedCompanies, matchedSkills),
            followUpHints?.keywordHints.orEmpty(),
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

    private fun resolveRecentHints(
        history: List<ChatMessage>,
        resume: com.dd3ok.whoamai.domain.Resume
    ): RecentOwnDomainHints? {
        if (history.isEmpty()) return null

        return history.asReversed()
            .take(6)
            .mapNotNull { message ->
                val normalized = normalizeResumeQuery(message.text)
                val company = resume.experiences.firstOrNull { exp ->
                    normalized.contains(normalizeResumeQuery(exp.company)) ||
                        exp.aliases.any { alias -> normalized.contains(normalizeResumeQuery(alias)) }
                }?.company
                val project = resume.projects.firstOrNull { project ->
                    normalized.contains(normalizeResumeQuery(project.title))
                }?.title
                val skills = resume.skills
                    .filter { normalized.contains(normalizeResumeQuery(it)) }
                    .distinct()

                if (company == null && project == null && skills.isEmpty()) {
                    null
                } else {
                    RecentOwnDomainHints(
                        company = company ?: project?.let { title ->
                            resume.projects.firstOrNull { it.title == title }?.company
                        },
                        project = project,
                        skills = skills,
                        keywordHints = buildList {
                            company?.let(::add)
                            project?.let(::add)
                            addAll(skills)
                        }
                    )
                }
            }
            .firstOrNull()
    }

    private fun mergeKeywords(
        extractedKeywords: List<String>,
        recentKeywordHints: List<String>,
        company: String?,
        project: String?
    ): List<String> {
        return (listOfNotNull(company, project) + recentKeywordHints + extractedKeywords)
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

    private data class RecentOwnDomainHints(
        val company: String? = null,
        val project: String? = null,
        val skills: List<String> = emptyList(),
        val keywordHints: List<String> = emptyList()
    )

}
