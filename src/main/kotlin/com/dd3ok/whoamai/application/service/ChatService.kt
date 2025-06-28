package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.VectorDBPort
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.StreamMessage
import com.dd3ok.whoamai.infrastructure.adapter.out.persistence.ResumeChunk
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.google.genai.types.Content
import com.google.genai.types.Part
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

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
    }

    @PostConstruct
    fun initialize() {
        logger.info("Loading resume data into memory...")
        this.resume = loadResume()
        if (this.resume.name.isBlank()) {
            logger.error("FATAL: Resume data could not be loaded from 'resume.json'. Chat service may not work as expected.")
        } else {
            logger.info("Resume data for '{}' loaded successfully into memory.", this.resume.name)
        }
    }

    suspend fun reindexResumeData(): String {
        if (!::resume.isInitialized || resume.name.isBlank()) {
            val errorMessage = "Resume data is not loaded in memory. Cannot perform re-indexing. Check if 'resume.json' is valid."
            logger.error(errorMessage)
            return errorMessage
        }

        logger.info("Starting on-demand resume indexing process for '{}'...", this.resume.name)
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

    private fun generateResumeChunks(resume: Resume): List<ResumeChunk> {
        val chunks = mutableListOf<ResumeChunk>()
        try {
            chunks.add(ResumeChunk(id = "summary", type = "summary", content = "저는 ${resume.name}입니다. ${resume.summary}", source = objectMapper.convertValue(mapOf("name" to resume.name, "summary" to resume.summary, "blog" to resume.blog))))
            chunks.add(ResumeChunk(id = "skills", type = "skills", content = "보유하고 있는 주요 기술은 ${resume.skills.joinToString(", ")} 등 입니다.", skills = resume.skills, source = objectMapper.convertValue(mapOf("skills" to resume.skills))))

            val experienceSummaryForAI = "전체 경력 기간 정보는 다음과 같습니다: " +
                    resume.experiences.joinToString("; ") { exp ->
                        "${exp.company}에서 ${exp.period.start}부터 ${exp.period.end}까지 근무"
                    } + ". 이 정보를 바탕으로 총 경력을 계산해서 알려주세요."
            chunks.add(ResumeChunk(
                id = "experience_total_summary",
                type = "summary",
                content = experienceSummaryForAI,
                source = objectMapper.convertValue(mapOf("items" to resume.experiences))
            ))

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
                contentBuilder.appendLine("${exp.company}에서 근무한 경력 정보입니다.")
                contentBuilder.appendLine("근무 기간은 ${exp.period.start}부터 ${exp.period.end}까지이며, ${exp.position}으로 근무했습니다.")
                chunks.add(ResumeChunk(
                    id = "experience_${exp.company.replace(" ", "_")}",
                    type = "experience",
                    content = contentBuilder.toString(),
                    company = exp.company,
                    source = objectMapper.convertValue(exp)
                ))
            }

            resume.projects.forEach { proj ->
                val projectId = "project_${proj.title.replace(Regex("\\s+"), "_")}"
                val projectContent = """
                프로젝트 '${proj.title}'에 대한 상세 정보입니다.
                - 소속: ${proj.company}
                - 기간: ${proj.period.start} ~ ${proj.period.end}
                - 설명: ${proj.description}
                - 주요 기술: ${proj.skills.joinToString(", ")}
            """.trimIndent()
                chunks.add(ResumeChunk(
                    id = projectId,
                    type = "project",
                    content = projectContent,
                    company = proj.company,
                    skills = proj.skills,
                    source = objectMapper.convertValue(proj)
                ))
            }

            logger.info("Generated ${chunks.size} chunks from resume data.")
        } catch (e: Exception) {
            logger.error("Error during chunk generation: ${e.message}", e)
        }
        return chunks
    }

    /**
     * 사용자의 질문 의도를 3가지 유형(CHIT_CHAT, RESUME_RAG, GENERAL_CONVERSATION)으로 분류합니다.
     */
    private fun classifyQueryType(userPrompt: String): QueryType {
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()
        val resumeKeywords = listOf(
            "경력", "이력", "회사", "프로젝트", "스킬", "기술", "학력", "학교", "자격증",
            "mbti", "취미", "관심사", "지마켓", "미라콤", "이력서", this.resume.name.lowercase() // '유인재'를 동적으로 변경
        )
        // 이력서 키워드가 있으면 RAG, 없으면 모두 NON_RAG
        if (resumeKeywords.any { normalizedQuery.contains(it) } || resume.projects.any { userPrompt.contains(it.title) }) {
            logger.info("Query type classified as: RESUME_RAG")
            return QueryType.RESUME_RAG
        }
        logger.info("Query type classified as: NON_RAG")
        return QueryType.NON_RAG
    }

    /**
     * RESUME_RAG 유형의 질문에 대해서만 RAG 컨텍스트를 검색하고 반환합니다.
     */
    private suspend fun retrieveContextsForResumeQuery(userPrompt: String): List<String> {
        if (!::resume.isInitialized) return emptyList()
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()

        val matchedProject = resume.projects.find { proj -> userPrompt.contains(proj.title) }
        if (matchedProject != null) {
            logger.info("Topic detected: Specific Project ('${matchedProject.title}'). Retrieving chunk by ID.")
            val projectId = "project_${matchedProject.title.replace(Regex("\\s+"), "_")}"
            return vectorDBPort.findChunkById(projectId)?.let { listOf(it) } ?: emptyList()
        }

        when {
            listOf("업무", "프로젝트", "project", "수행과제", "어떤일", "무슨일").any { normalizedQuery.contains(it) } -> {
                logger.info("Topic detected: General Projects. Retrieving all project chunks.")
                return resume.projects.mapNotNull { proj ->
                    val projectId = "project_${proj.title.replace(Regex("\\s+"), "_")}"
                    vectorDBPort.findChunkById(projectId)
                }
            }
            listOf("총 경력", "총경력", "전체경력", "경력기간", "몇년차", "경력몇년").any { normalizedQuery.contains(it) } -> {
                logger.info("Topic detected: Total Experience. Retrieving specific chunk.")
                return vectorDBPort.findChunkById("experience_total_summary")?.let { listOf(it) } ?: emptyList()
            }
            listOf("경력", "이력", "experience", "회사", "다녔", "일했").any { normalizedQuery.contains(it) } -> {
                logger.info("Topic detected: General Experience. Retrieving all experience chunks.")
                return resume.experiences.mapNotNull { exp -> vectorDBPort.findChunkById("experience_${exp.company.replace(" ", "_")}") }
            }
            listOf("mbti", "성격").any { normalizedQuery.contains(it) } -> {
                logger.info("Topic detected: MBTI. Retrieving chunk by ID.")
                return vectorDBPort.findChunkById("mbti")?.let { listOf(it) } ?: emptyList()
            }
            listOf("취미", "hobby", "게임", "운동").any { normalizedQuery.contains(it) } -> {
                logger.info("Topic detected: Hobby. Retrieving chunk by ID.")
                return vectorDBPort.findChunkById("hobbies")?.let { listOf(it) } ?: emptyList()
            }
        }

        logger.info("No specific keywords detected. Performing general vector search.")
        return vectorDBPort.searchSimilarResumeSections(userPrompt, topK = 3)
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        if (!::resume.isInitialized) {
            val errorMessage = "오류: 이력서 데이터가 로드되지 않았습니다. 관리자에게 문의해주세요."
            logger.error("Cannot process chat stream because resume data is not initialized.")
            return flowOf(errorMessage)
        }

        val userId = message.uuid
        val userPrompt = message.content

        // 1. 질문 의도를 먼저 분류합니다.
        val queryType = classifyQueryType(userPrompt)
        val domainHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)

        // 2. 질문 의도에 따라 대화 기록(pastHistory)과 RAG 컨텍스트(relevantContexts)를 다르게 구성합니다.
        val (pastHistory, relevantContexts) = when (queryType) {
            QueryType.NON_RAG -> {
                val history = createApiHistoryWindow(domainHistory)
                Pair(history, emptyList())
            }
            QueryType.RESUME_RAG -> {
                val history = createApiHistoryWindow(domainHistory)
                val contexts = retrieveContextsForResumeQuery(userPrompt)
                Pair(history, contexts)
            }
        }


        // 3. 구성된 데이터를 기반으로 프롬프트를 생성합니다.
        val apiHistory = if (relevantContexts.isNotEmpty()) {
            logger.info("Found ${relevantContexts.size} relevant contexts. Creating RAG prompt.")
            createRagPrompt(pastHistory, userPrompt, relevantContexts)
        } else {
            logger.info("No relevant context found. Creating general conversation prompt.")
            createGeneralPrompt(pastHistory, userPrompt)
        }

        val responseFlow = geminiPort.generateChatContent(apiHistory)
        val modelResponseBuilder = StringBuilder()

        return responseFlow
            .onEach { chunk -> modelResponseBuilder.append(chunk) }
            .onCompletion { cause ->
                if (cause == null) {
                    val fullResponse = modelResponseBuilder.toString()
                    if (fullResponse.isNotBlank()) {
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

    private fun createRagPrompt(history: List<Content>, userPrompt: String, contexts: List<String>): List<Content> {
        val contextString = contexts.joinToString("\n---\n")
        val ragContextPrompt  = """
            아래 "이력서 정보"를 바탕으로 답변해야 합니다.

            --- 이력서 정보 ---
            $contextString
            --- 정보 끝 ---
            
            질문: $userPrompt
        """.trimIndent()

        val contentBuilder = mutableListOf<Content>()
        contentBuilder.addAll(history)
        contentBuilder.add(Content.fromParts(Part.fromText(ragContextPrompt )))
        return contentBuilder
    }

    private fun createGeneralPrompt(history: List<Content>, userPrompt: String): List<Content> {
        val contentBuilder = mutableListOf<Content>()
        contentBuilder.addAll(history)
        contentBuilder.add(Content.fromParts(Part.fromText(userPrompt)))
        return contentBuilder
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
        return recentMessages.map { msg ->
            Content.builder().role(msg.role).parts(Part.fromText(msg.text)).build()
        }
    }

    private suspend fun summarizeHistoryIfNeeded(originalHistory: ChatHistory): ChatHistory {
        val totalTokens = originalHistory.history.sumOf { estimateTokens(it.text) }
        if (totalTokens < SUMMARY_TRIGGER_TOKENS || originalHistory.history.size < SUMMARY_SOURCE_MESSAGES + 2) {
            return originalHistory
        }

        logger.info("History for user {} exceeds token threshold. Starting summarization.", originalHistory.userId)
        val messagesToSummarize = originalHistory.history.take(SUMMARY_SOURCE_MESSAGES)
        val recentMessages = originalHistory.history.drop(SUMMARY_SOURCE_MESSAGES)
        val summarizationPrompt = """다음 대화는 챗봇의 이전 대화 기록입니다. 이 대화의 핵심 내용을 다른 AI 모델이 대화의 맥락을 이어갈 수 있도록 한두 문단으로 간결하게 요약해주세요. --- ${messagesToSummarize.joinToString("\n") { "[${it.role}]: ${it.text}" }} --- 요약:""".trimIndent()
        val summaryText = geminiPort.summerizeContent(summarizationPrompt)

        if (summaryText.isBlank()) {
            logger.warn("Summarization failed or returned empty. Skipping history modification.")
            return originalHistory
        }
        val summaryMessage = ChatMessage(role = "model", text = "이전 대화 요약: $summaryText")
        val newMessages = mutableListOf(summaryMessage).apply { addAll(recentMessages) }
        return ChatHistory(originalHistory.userId, newMessages)
    }

    private fun estimateTokens(text: String): Int {
        return (text.length * 1.5).toInt()
    }
}