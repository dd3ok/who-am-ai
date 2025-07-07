package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LLMRouter(
    private val geminiPort: GeminiPort,
    private val resumeProviderPort: ResumeProviderPort,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val routingPromptTemplate = """
        You are a master router. Your task is to analyze the user's question and determine the best way to answer it based on the provided resume context.
        You must return a JSON object with the following structure: {"queryType": "TYPE", "company": "COMPANY_NAME", "skills": ["SKILL_1", "SKILL_2"], "keywords": ["KEYWORD_1"]}

        1.  **Analyze the "User's Question"**.
        2.  **Determine the `queryType`**:
            * If the question is about the resume owner's career, projects, skills, or personal information (e.g., "Tell me about yourself", "What projects did you do at Gmarket?", "What is your MBTI?"), set it to "RESUME_RAG".
            * Otherwise, for general conversation, small talk, or questions unrelated to the resume, set it to "NON_RAG".
        3.  **Extract Entities (only for RESUME_RAG)**:
            * `company`: If a specific company from the "Companies List" is mentioned, extract its official name.
            * `skills`: If any skills from the "Skills List" are mentioned, extract them.
            * `keywords`: Extract any other important keywords from the question that could be useful for a search (e.g., "login system", "security").
        4.  **Respond ONLY with the JSON object.**

        ---
        ## Context for Routing
        * **Resume Owner's Name**: {resume_owner_name}
        * **Companies List**: {companies_list}
        * **Skills List**: {skills_list}
        ---
        ## User's Question
        {question}
    """.trimIndent()

    suspend fun route(userPrompt: String): RouteDecision {
        val resume = resumeProviderPort.getResume()
        val companies = resume.experiences.map { it.company }
        val skills = resume.skills

        val prompt = routingPromptTemplate
            .replace("{resume_owner_name}", resume.name)
            .replace("{companies_list}", companies.joinToString(", "))
            .replace("{skills_list}", skills.joinToString(", "))
            .replace("{question}", userPrompt)

        try {
            val responseJson = geminiPort.summerizeContent(prompt)
            if (responseJson.isBlank()) {
                logger.warn("LLM Router returned a blank response. Defaulting to NON_RAG.")
                return RouteDecision(QueryType.NON_RAG)
            }
            // LLM이 JSON 블록(```json ... ```)으로 감싸서 반환하는 경우가 많으므로, 순수 JSON만 추출
            val pureJson = responseJson.substringAfter("```json").substringBeforeLast("```").trim()
            return objectMapper.readValue(pureJson)
        } catch (e: Exception) {
            logger.error("Error parsing LLM Router response. Defaulting to NON_RAG. Error: ${e.message}", e)
            return RouteDecision(QueryType.NON_RAG)
        }
    }
}