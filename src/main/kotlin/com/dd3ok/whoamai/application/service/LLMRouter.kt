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

        try {
            val responseJson = geminiPort.generateContent(prompt, "routing")
            if (responseJson.isBlank()) {
                logger.warn("LLM Router returned a blank response. Defaulting to NON_RAG.")
                return RouteDecision(QueryType.NON_RAG)
            }
            val pureJson = responseJson.substringAfter("```json").substringBeforeLast("```").trim()
            return objectMapper.readValue(pureJson)
        } catch (e: Exception) {
            logger.error("Error parsing LLM Router response. Defaulting to NON_RAG. Error: ${e.message}", e)
            return RouteDecision(QueryType.NON_RAG)
        }
    }
}