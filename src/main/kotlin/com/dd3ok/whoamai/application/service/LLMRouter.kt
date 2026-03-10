package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LLMRouter(
    private val resumeProviderPort: ResumeProviderPort,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun route(userPrompt: String): RouteDecision {
        val resume = resumeProviderPort.getResume()
        val normalized = normalizeResumeQuery(userPrompt)
        val nameFragments = NameFragmentExtractor.extract(resume.name)

        if (isHardBlocked(normalized)) {
            logger.info("Routing short-circuited by hard block keywords. Returning NON_RAG.")
            return recordDecision(RouteDecision(QueryType.NON_RAG), "hard_block")
        }

        if (!isLikelyResumeQuestion(userPrompt, normalized, resume, nameFragments)) {
            logger.info("Routing defaulted to NON_RAG because prompt is not a targeted resume question.")
            return recordDecision(RouteDecision(QueryType.NON_RAG), "question_guard")
        }

        val heuristicDecision = buildHeuristicDecision(userPrompt, normalized, resume)
        if (heuristicDecision != null) {
            logger.info("Routing resolved by heuristic path: {}", heuristicDecision)
            return recordDecision(heuristicDecision, "heuristic")
        }

        val decision = RouteDecision(
            queryType = QueryType.RESUME_RAG,
            company = null,
            skills = null,
            keywords = extractKeywords(userPrompt, emptyList(), emptyList()).ifEmpty { null }
        )
        return recordDecision(decision, "intent_only")
    }

    private fun isHardBlocked(normalizedPrompt: String): Boolean {
        if (HARD_BLOCK_EXPLICIT_PHRASES.any { normalizedPrompt.contains(it) }) {
            return true
        }
        val hasSelfRef = SELF_REFERENCE_TOKENS.any { normalizedPrompt.contains(it) }
        val hasModelIdentityIntent = MODEL_IDENTITY_TOKENS.any { normalizedPrompt.contains(it) }
        return hasSelfRef && hasModelIdentityIntent
    }

    companion object {
        private val HARD_BLOCK_EXPLICIT_PHRASES = listOf(
            "너는뭐로만들어졌",
            "넌뭐로만들어졌",
            "너의스택",
            "넌무슨스택",
            "너는무슨모델",
            "너는누가만들었",
            "누가너를만들었",
            "어떤프레임워크로만들"
        ).map { it.lowercase() }

        private val SELF_REFERENCE_TOKENS = listOf(
            "너는", "넌", "니가", "너의", "누구야", "누구니"
        ).map { it.lowercase() }

        private val MODEL_IDENTITY_TOKENS = listOf(
            "만들어졌", "만들었", "스택", "stack", "gpt", "제미나이", "gemini", "구글", "google", "openai", "llm", "모델", "프레임워크"
        ).map { it.lowercase() }

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
        resume: com.dd3ok.whoamai.domain.Resume
    ): RouteDecision? {
        val matchedCompanies = resume.experiences
            .filter { exp ->
                normalizedPrompt.contains(normalizeResumeQuery(exp.company)) ||
                    exp.aliases.any { alias -> normalizedPrompt.contains(normalizeResumeQuery(alias)) }
            }
            .map { it.company }
            .distinct()

        val matchedSkills = resume.skills
            .filter { normalizedPrompt.contains(normalizeResumeQuery(it)) }
            .distinct()

        val hasResumeSignal = containsResumeSlots(normalizedPrompt, resume)
        if (!hasResumeSignal && matchedCompanies.isEmpty() && matchedSkills.isEmpty()) {
            return null
        }

        val extractedKeywords = extractKeywords(userPrompt, matchedCompanies, matchedSkills)
        return RouteDecision(
            queryType = QueryType.RESUME_RAG,
            company = matchedCompanies.firstOrNull(),
            skills = matchedSkills.ifEmpty { null },
            keywords = extractedKeywords.ifEmpty { null }
        )
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
