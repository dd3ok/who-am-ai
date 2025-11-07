package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.config.PromptProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LLMRouter(
    private val geminiPort: GeminiPort,
    private val resumeProviderPort: ResumeProviderPort,
    private val promptProperties: PromptProperties, // PromptProperties 주입
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun route(userPrompt: String): RouteDecision {
        val resume = resumeProviderPort.getResume()
        val companies = resume.experiences.map { it.company }
        val skills = resume.skills

        val prompt = promptProperties.routingTemplate
            .replace("{resume_owner_name}", resume.name)
            .replace("{companies_list}", companies.joinToString(", "))
            .replace("{skills_list}", skills.joinToString(", "))
            .replace("{question}", userPrompt)

        val responseJson = geminiPort.generateContent(prompt, "routing")
        if (responseJson.isBlank()) {
            logger.warn("LLM Router returned a blank response. Defaulting to NON_RAG.")
            return RouteDecision(QueryType.NON_RAG)
        }

        val jsonPayload = extractJsonBlock(responseJson)

        return try {
            objectMapper.readValue(jsonPayload)
        } catch (e: Exception) {
            logger.error(
                "Error parsing LLM Router response. Defaulting to NON_RAG. payload={}",
                jsonPayload.take(200),
                e
            )
            RouteDecision(QueryType.NON_RAG)
        }
    }

    private fun extractJsonBlock(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }

        if (trimmed.contains("```json")) {
            val block = trimmed.substringAfter("```json").substringBefore("```").trim()
            if (block.isNotEmpty()) {
                return block
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1).trim()
        }

        return trimmed
    }
}
