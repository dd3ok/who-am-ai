package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.IntentDecision
import com.dd3ok.whoamai.common.config.RoutingProperties
import org.springframework.stereotype.Component

/**
 * 작업 목적: 기본 RAG를 기준으로 하되, 명백히 일반 대화로 보이는 요청만 예외 처리한다.
 * 주요 로직: 설정 기반 예외 키워드와 Resume 정보(회사/스킬/이름)를 활용해 간단한 Intent 결정을 수행한다.
 */
@Component
class QueryIntentDecider(
    private val routingProperties: RoutingProperties,
    private val resumeProviderPort: ResumeProviderPort
) {

    private val whitespaceRegex = Regex("\\s+")

    fun decide(prompt: String): IntentDecision {
        val normalized = prompt.lowercase()
        val condensed = normalized.replace(whitespaceRegex, "")

        if (matchesNonResumeKeyword(normalized)) {
            return IntentDecision.general()
        }

        val resume = resumeProviderPort.getResume()
        val companyHint = resume.experiences.firstOrNull { experience ->
            val companyToken = experience.company.lowercase()
            normalized.contains(companyToken) || experience.aliases.any { alias ->
                val normalizedAlias = alias.lowercase()
                normalized.contains(normalizedAlias) || condensed.contains(normalizedAlias.replace(whitespaceRegex, ""))
            }
        }?.company

        val skillHints = resume.skills
            .map { it.lowercase() to it }
            .filter { (lowerSkill, _) -> normalized.contains(lowerSkill) }
            .map { it.second }
            .distinct()
            .take(MAX_SKILL_HINTS)

        val keywordHints = mutableListOf<String>()
        if (resume.name.isNotBlank()) {
            val resumeName = resume.name.lowercase()
            val fragments = buildNameFragments(resumeName)
            if (fragments.any { normalized.contains(it) }) {
                keywordHints += resume.name
            }
        }

        return IntentDecision.resume(
            companyHint = companyHint,
            skillHints = skillHints,
            keywordHints = keywordHints
        )
    }

    private fun matchesNonResumeKeyword(normalizedPrompt: String): Boolean {
        if (routingProperties.nonResumeKeywords.isEmpty()) {
            return false
        }
        return routingProperties.nonResumeKeywords.any { keyword ->
            normalizedPrompt.contains(keyword.lowercase())
        }
    }

    private fun buildNameFragments(resumeName: String): List<String> {
        if (resumeName.isBlank()) return emptyList()
        val normalized = resumeName.replace(whitespaceRegex, "")
        if (normalized.length <= 1) return listOf(normalized)
        val fragments = mutableListOf<String>()
        for (i in normalized.indices) {
            for (j in i + 2..normalized.length) {
                fragments.add(normalized.substring(i, j))
            }
        }
        return fragments.distinct()
    }

    companion object {
        private const val MAX_SKILL_HINTS = 3
    }
}
