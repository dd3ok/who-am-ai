package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.service.PromptProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LLMRouter(
    private val geminiPort: GeminiPort,
    private val resumeProviderPort: ResumeProviderPort,
    private val promptTemplateService: PromptProvider,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun route(userPrompt: String): RouteDecision {
        val resume = resumeProviderPort.getResume()
        val companies = resume.experiences.map { it.company }
        val skills = resume.skills
        val normalized = normalize(userPrompt)

        if (isHardBlocked(normalized)) {
            logger.info("Routing short-circuited by hard block keywords. Returning NON_RAG.")
            return RouteDecision(QueryType.NON_RAG)
        }

        if (!containsResumeIntent(normalized) || !containsResumeSlots(normalized, resume)) {
            logger.info("Routing defaulted to NON_RAG because no resume intent/slots found.")
            return RouteDecision(QueryType.NON_RAG)
        }

        val prompt = promptTemplateService.renderRoutingTemplate(
            resumeOwnerName = resume.name,
            companies = companies,
            skills = skills,
            question = userPrompt
        )

        val responseJson = geminiPort.generateContent(prompt, "routing").trim()
        if (responseJson.isBlank()) {
            logger.warn("LLM Router returned a blank response. Defaulting to NON_RAG.")
            return RouteDecision(QueryType.NON_RAG)
        }

        return try {
            objectMapper.readValue(responseJson)
        } catch (e: Exception) {
            logger.error(
                "Error parsing LLM Router response. Defaulting to NON_RAG. payload={}",
                responseJson.take(200),
                e
            )
            RouteDecision(QueryType.NON_RAG)
        }
    }

    private fun isHardBlocked(normalizedPrompt: String): Boolean {
        return HARD_BLOCK_KEYWORDS.any { normalizedPrompt.contains(it) }
    }

    private fun containsResumeIntent(normalizedPrompt: String): Boolean {
        return RESUME_INTENT_KEYWORDS.any { normalizedPrompt.contains(it) }
    }

    companion object {
        private val HARD_BLOCK_KEYWORDS = listOf(
            "너는", "넌", "니가", "너무엇", "너뭐", "누구야", "누구니", "뭐야",
            "만들어", "만든사람", "개발자", "프레임워크",
            "스택", "stack", "gpt", "제미나이", "gemini", "구글", "google", "openai", "llm", "모델"
        ).map { it.lowercase() }

        private val RESUME_INTENT_KEYWORDS = listOf(
            "경력", "직무", "회사", "프로젝트", "포트폴리오", "이력서", "요약",
            "스킬", "기술", "자격증", "학력", "mbti", "관심사", "취미", "소개"
        ).map { it.lowercase() }

        private val SLOT_KEYWORDS = listOf(
            "경력", "직무", "회사", "프로젝트", "스킬", "기술", "자격증", "cert",
            "학력", "학교", "전공", "mbti", "관심", "취미", "포트폴리오", "이력서"
        ).map { it.lowercase() }
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private fun containsResumeSlots(normalizedPrompt: String, resume: com.dd3ok.whoamai.domain.Resume): Boolean {
        if (SLOT_KEYWORDS.any { normalizedPrompt.contains(it) }) return true

        val companyHit = resume.experiences.any { exp ->
            normalizedPrompt.contains(normalize(exp.company)) ||
                exp.aliases.any { alias -> normalizedPrompt.contains(normalize(alias)) }
        }
        if (companyHit) return true

        val projectHit = resume.projects.any { proj ->
            normalizedPrompt.contains(normalize(proj.title)) ||
                proj.skills.any { skill -> normalizedPrompt.contains(normalize(skill)) } ||
                proj.tags.any { tag -> normalizedPrompt.contains(normalize(tag)) }
        }
        if (projectHit) return true

        val skillHit = resume.skills.any { normalizedPrompt.contains(normalize(it)) }
        if (skillHit) return true

        val educationHit = resume.education.any { edu ->
            normalizedPrompt.contains(normalize(edu.school)) || normalizedPrompt.contains(normalize(edu.major))
        }
        return educationHit
    }
}
