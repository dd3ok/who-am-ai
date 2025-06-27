package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.domain.*
import com.dd3ok.whoamai.infrastructure.adapter.out.persistence.ResumeChunk
import com.dd3ok.whoamai.infrastructure.adapter.out.persistence.VectorDBPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.google.genai.types.Content
import com.google.genai.types.Part
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

@Service
class ChatService(
    private val geminiPort: GeminiPort,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val vectorDBPort: VectorDBPort,
    private val objectMapper: ObjectMapper
) : ChatUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var resume: Resume

    companion object {
        private const val API_WINDOW_TOKENS = 2048
        private const val SUMMARY_TRIGGER_TOKENS = 4096
        private const val SUMMARY_SOURCE_MESSAGES = 5
        private const val RESUME_SEARCH_TOP_K = 3
    }

    suspend fun reindexResumeData(): String {
        logger.info("Starting on-demand resume indexing process...")
        this.resume = loadResume()
        if (this.resume.name.isBlank()) {
            val errorMessage = "Resume data is empty. Indexing process failed. Please check 'resume.json' file."
            logger.error(errorMessage)
            return errorMessage
        }
        val resumeChunks = generateResumeChunks(this.resume)
        return if (resumeChunks.isNotEmpty()) {
            val indexedCount = vectorDBPort.indexResume(resumeChunks)
            val successMessage = "Resume indexing process finished. Indexed $indexedCount documents."
            logger.info(successMessage)
            successMessage
        } else {
            val warnMessage = "No resume chunks were generated from the resume data. Indexing skipped."
            logger.warn(warnMessage)
            warnMessage
        }
    }

    private fun loadResume(): Resume {
        return try {
            val resource = ClassPathResource("resume.json")
            objectMapper.readValue(resource.inputStream, Resume::class.java)
        } catch (e: Exception) {
            logger.error("Fatal: Failed to load and parse resume.json. Service may not function correctly.", e)
            Resume()
        }
    }

    private fun calculateTotalExperience(experiences: List<Experience>): String {
        if (experiences.isEmpty()) return "경력 정보가 없습니다."
        try {
            val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val minDate = experiences.minOf { LocalDate.parse(it.period.start + "-01", dateFormatter) }
            val maxDate = experiences.maxOf { LocalDate.parse(it.period.end + "-01", dateFormatter) }
            val period = Period.between(minDate, maxDate.plusMonths(1))
            val years = period.years
            val months = period.months
            val startStr = minDate.format(DateTimeFormatter.ofPattern("yyyy년 MM월"))
            val endStr = maxDate.format(DateTimeFormatter.ofPattern("yyyy년 MM월"))
            return "총 경력은 약 ${years}년 ${months}개월입니다. (${startStr}부터 ${endStr}까지)"
        } catch (e: Exception) {
            logger.error("Failed to calculate total experience period.", e)
            return "총 경력 기간을 계산하는 데 실패했습니다."
        }
    }

    private fun generateResumeChunks(resume: Resume): List<ResumeChunk> {
        val chunks = mutableListOf<ResumeChunk>()
        try {
            chunks.add(ResumeChunk(id = "summary", type = "summary", content = "저는 ${resume.name}입니다. ${resume.summary} 운영 중인 기술 블로그 주소는 ${resume.blog} 입니다.", source = objectMapper.convertValue(mapOf("name" to resume.name, "summary" to resume.summary, "blog" to resume.blog))))
            chunks.add(ResumeChunk(id = "skills", type = "skills", content = "보유하고 있는 주요 기술은 ${resume.skills.joinToString(", ")} 등 입니다.", skills = resume.skills, source = objectMapper.convertValue(mapOf("skills" to resume.skills))))
            val totalExperienceString = calculateTotalExperience(resume.experiences)
            chunks.add(ResumeChunk(id = "experience_total_summary", type = "summary", content = totalExperienceString, source = objectMapper.convertValue(mapOf("total_experience" to totalExperienceString))))
            val educationContent = resume.education.joinToString("\n") { edu -> "${edu.school}에서 ${edu.major}을 전공했으며(${edu.period.start} ~ ${edu.period.end}), ${edu.degree} 학위를 받았습니다." }
            if (educationContent.isNotBlank()) { chunks.add(ResumeChunk(id = "education", type = "education", content = "학력 정보는 다음과 같습니다.\n$educationContent", source = objectMapper.convertValue(mapOf("items" to resume.education)))) }
            val certificateContent = resume.certificates.joinToString("\n") { cert -> "${cert.issuedAt}에 ${cert.issuer}에서 발급한 ${cert.title} 자격증을 보유하고 있습니다." }
            if (certificateContent.isNotBlank()) { chunks.add(ResumeChunk(id = "certificates", type = "certificate", content = "보유 자격증은 다음과 같습니다.\n$certificateContent", source = objectMapper.convertValue(mapOf("items" to resume.certificates)))) }
            val hobbyContent = resume.hobbies.joinToString("\n") { hobby -> "${hobby.category}으로는 ${hobby.items.joinToString(", ")} 등을 즐깁니다." }
            if (hobbyContent.isNotBlank()) { chunks.add(ResumeChunk(id = "hobbies", type = "hobby", content = "주요 취미는 다음과 같습니다.\n$hobbyContent", source = objectMapper.convertValue(mapOf("items" to resume.hobbies)))) }
            val interestContent = resume.interests.joinToString(", ")
            if (interestContent.isNotBlank()) { chunks.add(ResumeChunk(id = "interests", type = "interest", content = "최근 주요 관심사는 ${interestContent} 등 입니다.", source = objectMapper.convertValue(mapOf("items" to resume.interests)))) }
            if (resume.mbti.isNotBlank()) { chunks.add(ResumeChunk(id = "mbti", type = "personality", content = "저의 MBTI는 ${resume.mbti}입니다.", source = objectMapper.convertValue(mapOf("mbti" to resume.mbti)))) }
            resume.experiences.forEach { exp ->
                val contentBuilder = StringBuilder()
                contentBuilder.appendLine("${exp.company}에서 근무한 경력과 프로젝트 정보입니다.")
                contentBuilder.appendLine("근무 기간은 ${exp.period.start}부터 ${exp.period.end}까지이며, ${exp.position}으로 근무했습니다.")
                val companyProjects = resume.projects.filter { it.company == exp.company }
                if (companyProjects.isNotEmpty()) {
                    contentBuilder.appendLine("\n이곳에서 수행한 주요 프로젝트는 다음과 같습니다.")
                    companyProjects.forEach { proj ->
                        contentBuilder.appendLine("\n- 프로젝트명: ${proj.title}")
                        contentBuilder.appendLine("  - 설명: ${proj.description}")
                        contentBuilder.appendLine("  - 사용 기술: ${proj.skills.joinToString(", ")}")
                    }
                }
                chunks.add(ResumeChunk(id = "experience_${exp.company.replace(" ", "_")}", type = "experience", content = contentBuilder.toString(), company = exp.company, skills = companyProjects.flatMap { it.skills }.distinct(), source = objectMapper.convertValue(exp)))
            }
            logger.info("Generated ${chunks.size} chunks from resume.json")
        } catch (e: Exception) {
            logger.error("Error during chunk generation: ${e.message}", e)
        }
        return chunks
    }

    private fun extractFiltersFromQuery(query: String): Document? {
        val conditions = mutableListOf<Document>()
        val matchedCompanies = mutableSetOf<String>()
        resume.experiences.forEach { experience ->
            val allCompanyNames = listOf(experience.company) + experience.aliases
            if (allCompanyNames.any { query.contains(it, ignoreCase = true) }) {
                matchedCompanies.add(experience.company)
            }
        }
        if (matchedCompanies.isNotEmpty()) { conditions.add(Document("company", Document("\$in", matchedCompanies.toList()))) }
        val skills = resume.skills.distinct()
        skills.forEach { skill -> if (Regex("\\b${skill}\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)) { conditions.add(Document("skills", Document("\$eq", skill))) } }
        if (conditions.isEmpty()) return null
        if (conditions.size == 1) return conditions.first()
        return Document("\$and", conditions)
    }

    // --- [수정] 최종 폴백(Fallback) 검색을 제거하여 일반 대화로 유도 ---
    private suspend fun routeAndRetrieveContexts(userPrompt: String): List<String> {
        val normalizedQuery = userPrompt.replace(" ", "").lowercase()
        val generalTopicKeywords = mapOf(
            listOf("총경력", "전체경력", "경력기간", "몇년") to "total_experience",
            listOf("경력", "experience", "회사", "다녔", "일했", "이력") to "experience",
            listOf("프로젝트", "project", "플젝") to "project",
            listOf("학력", "학교", "대학교", "전공", "졸업") to "education",
            listOf("기술", "스킬", "스택", "skill", "stack") to "skills",
            listOf("mbti", "성격") to "mbti"
        )
        for ((keywords, topic) in generalTopicKeywords) {
            if (keywords.any { normalizedQuery.contains(it) }) {
                logger.info("General query detected for topic: '$topic'. Retrieving chunk(s) by ID.")
                return when (topic) {
                    "total_experience" -> vectorDBPort.findChunkById("experience_total_summary")?.let { listOf(it) } ?: emptyList()
                    "experience", "project" -> {
                        resume.experiences.mapNotNull { exp -> vectorDBPort.findChunkById("experience_${exp.company.replace(" ", "_")}") }
                    }
                    "education" -> vectorDBPort.findChunkById("education")?.let { listOf(it) } ?: emptyList()
                    "skills" -> vectorDBPort.findChunkById("skills")?.let { listOf(it) } ?: emptyList()
                    "mbti" -> vectorDBPort.findChunkById("mbti")?.let { listOf(it) } ?: emptyList()
                    else -> emptyList()
                }
            }
        }
        val filter = extractFiltersFromQuery(userPrompt)
        if (filter != null) {
            logger.info("Specific query detected. Applying metadata filter to search: $filter")
            return vectorDBPort.searchSimilarResumeSections(userPrompt, topK = RESUME_SEARCH_TOP_K, filter = filter)
        }

        // 키워드나 필터에 걸리지 않으면, 빈 리스트를 반환하여 일반 대화로 유도
        logger.info("No specific keywords or filters detected. Returning empty context to trigger general conversation.")
        return emptyList()
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        val userId = message.uuid
        val userPrompt = message.content
        val relevantContexts = routeAndRetrieveContexts(userPrompt)
        val apiHistory: List<Content>
        if (relevantContexts.isNotEmpty()) {
            logger.info("Found ${relevantContexts.size} relevant contexts. Creating augmented prompt.")
            apiHistory = createVectorSearchAugmentedPrompt(userPrompt, relevantContexts)
        } else {
            // 컨텍스트가 없으면, 이전 대화 기록만으로 일반 대화 수행
            logger.info("No relevant context found. Proceeding with general conversation.")
            val domainHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
            domainHistory.addMessage(ChatMessage(role = "user", text = userPrompt))
            apiHistory = createApiHistoryWindow(domainHistory)
        }
        val responseFlow = geminiPort.generateChatContent(apiHistory)
        val modelResponseBuilder = StringBuilder()
        return responseFlow
            .onEach { chunk -> modelResponseBuilder.append(chunk) }
            .onCompletion { cause ->
                if (cause == null) {
                    val fullResponse = modelResponseBuilder.toString()
                    if (fullResponse.isNotBlank()) {
                        val domainHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
                        domainHistory.addMessage(ChatMessage(role = "user", text = userPrompt))
                        domainHistory.addMessage(ChatMessage(role = "model", text = fullResponse))
                        val finalHistoryToSave = summarizeHistoryIfNeeded(domainHistory)
                        chatHistoryRepository.save(finalHistoryToSave)
                        logger.info("Successfully processed and saved chat history for user: {}", userId)
                    }
                } else {
                    logger.error("Chat stream failed for user: {}. History not saved.", userId, cause)
                }
            }
    }

    private fun createVectorSearchAugmentedPrompt(userPrompt: String, contexts: List<String>): List<Content> {
        val contextString = contexts.joinToString("\n---\n")
        val augmentedPrompt = """
            당신은 사용자의 질문에 답변하는 전문적인 인재 AI입니다.

            당신의 최우선 임무는 주어진 "이력서 정보"를 바탕으로 답변하는 것입니다.
            만약 사용자의 질문이 이력서 정보와 관련이 있다면, 반드시 해당 정보를 사용하여 답변해주세요.

            하지만, 만약 사용자의 질문이 이력서에 없는 내용(예: 개인적인 의견, 특정 주제에 대한 생각 등)에 관한 것이라면,
            이력서 정보에 얽매이지 않고 일반적인 AI 어시스턴트로서 자유롭게, 그러나 여전히 전문적이고 친절한 톤으로 답변할 수 있습니다.
            
            마크다운은 사용하지 않고 글은 띄어쓰기가 필요한 경우 띄어쓰기합니다.
            
            --- 이력서 정보 ---
            $contextString
            --- 정보 끝 ---

            사용자 질문: $userPrompt
        """.trimIndent()
        return listOf(Content.fromParts(Part.fromText(augmentedPrompt)))
    }

    private fun createApiHistoryWindow(domainHistory: ChatHistory): List<Content> {
        var currentTokens = 0
        val recentMessages = mutableListOf<ChatMessage>()
        for (msg in domainHistory.history.reversed()) {
            val estimatedTokens = estimateTokens(msg.text)
            if (currentTokens + estimatedTokens > API_WINDOW_TOKENS) { break }
            recentMessages.add(msg)
            currentTokens += estimatedTokens
        }
        recentMessages.reverse()
        return recentMessages.map { msg -> Content.builder().role(msg.role).parts(listOf(Part.fromText(msg.text))).build() }
    }

    private suspend fun summarizeHistoryIfNeeded(originalHistory: ChatHistory): ChatHistory {
        val totalTokens = originalHistory.history.sumOf { estimateTokens(it.text) }
        if (totalTokens < SUMMARY_TRIGGER_TOKENS || originalHistory.history.size < SUMMARY_SOURCE_MESSAGES + 2) { return originalHistory }
        logger.info("History for user {} exceeds token threshold. Starting summarization.", originalHistory.userId)
        val messagesToSummarize = originalHistory.history.take(SUMMARY_SOURCE_MESSAGES)
        val recentMessages = originalHistory.history.drop(SUMMARY_SOURCE_MESSAGES)
        val summarizationPrompt = """다음 대화는 챗봇의 이전 대화 기록입니다. 이 대화의 핵심 내용을 다른 AI 모델이 대화의 맥락을 이어갈 수 있도록 한두 문단으로 간결하게 요약해주세요. --- ${messagesToSummarize.joinToString("\n") { "[${it.role}]: ${it.text}" }} --- 요약:""".trimIndent()
        val summaryText = geminiPort.summerizeContent(summarizationPrompt)
        if (summaryText.isBlank()) {
            logger.warn("Summarization failed or returned empty. Skipping history modification.")
            return originalHistory
        }
        val summaryMessage = ChatMessage(role = "system", text = "이전 대화 요약: $summaryText")
        val newMessages = mutableListOf(summaryMessage).apply { addAll(recentMessages) }
        return ChatHistory(originalHistory.userId, newMessages)
    }

    private fun estimateTokens(text: String): Int {
        return (text.length * 1.5).toInt()
    }
}