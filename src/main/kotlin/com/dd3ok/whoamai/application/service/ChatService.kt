package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.config.PromptProperties
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.StreamMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ChatService(
    private val geminiPort: GeminiPort,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val resumePersistencePort: ResumePersistencePort,
    private val resumeProviderPort: ResumeProviderPort,
    private val promptProperties: PromptProperties
) : ChatUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val API_WINDOW_TOKENS = 2048
        const val SUMMARY_TRIGGER_TOKENS = 4096
        const val SUMMARY_SOURCE_MESSAGES = 5
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        val userId = message.uuid
        val userPrompt = message.content

        val domainHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)

        val pastHistory = createApiHistoryWindow(domainHistory)

        val queryType = classifyQueryType(userPrompt)

        val finalHistory = if (queryType == QueryType.RESUME_RAG) {
            logger.info("Query classified as RESUME_RAG. Retrieving context...")
            val relevantContexts = retrieveContextsForRagQuery(userPrompt)
            createRagPrompt(pastHistory, userPrompt, relevantContexts)
        } else {
            logger.info("Query classified as NON_RAG. Proceeding with conversational prompt.")
            createConversationalPrompt(pastHistory, userPrompt)
        }

        val modelResponseBuilder = StringBuilder()
        return geminiPort.generateChatContent(finalHistory)
            .onEach { chunk -> modelResponseBuilder.append(chunk) }
            .onCompletion { cause ->
                if (cause == null) {
                    val fullResponse = modelResponseBuilder.toString()
                    val currentHistory = chatHistoryRepository.findByUserId(userId) ?: ChatHistory(userId = userId)
                    currentHistory.addMessage(ChatMessage(role = "user", text = userPrompt))
                    currentHistory.addMessage(ChatMessage(role = "model", text = fullResponse))
                    val finalHistoryToSave = summarizeHistoryIfNeeded(currentHistory)
                    chatHistoryRepository.save(finalHistoryToSave)
                    logger.info("[SUCCESS] History saved.")
                } else {
                    logger.error("Chat stream failed with cause. History NOT saved.", cause)
                }
            }
    }

    /**
     * RAG 프롬프트를 생성합니다. 외부 설정 파일의 템플릿을 사용합니다.
     */
    private fun createRagPrompt(history: List<ChatMessage>, userPrompt: String, contexts: List<String>): List<ChatMessage> {
        val contextString = contexts.joinToString("\n---\n")
        val finalUserPrompt = promptProperties.ragTemplate
            .replace("{context}", contextString)
            .replace("{question}", userPrompt)

        return history + ChatMessage(role = "user", text = finalUserPrompt)
    }

    /**
     * 일반 대화 프롬프트를 생성합니다. 외부 설정 파일의 템플릿을 사용합니다.
     */
    private fun createConversationalPrompt(history: List<ChatMessage>, userPrompt: String): List<ChatMessage> {
        val finalUserPrompt = promptProperties.conversationalTemplate
            .replace("{question}", userPrompt)

        return history + ChatMessage(role = "user", text = finalUserPrompt)
    }

    /**
     * 규칙 기반으로 컨텍스트를 검색하는 로직입니다.
     */
    private suspend fun retrieveContextsForRagQuery(userPrompt: String): List<String> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()

        // 규칙 리스트 정의
        val rules = listOf(
            { q: String -> if (listOf("누구야", "누구세요", "소개", "자기소개", resume.name.lowercase()).any { q.contains(it) }) "summary" else null },
            { q: String -> if (listOf("총 경력", "총경력", "전체경력").any { q.contains(it) }) "experience_total_summary" else null },
            { q: String -> if (listOf("프로젝트", "project").any { q.contains(it) }) "projects" else null },
            { q: String -> if (listOf("경력", "이력", "회사").any { q.contains(it) }) "experiences" else null },
            { q: String -> if (listOf("자격증", "certificate").any { q.contains(it) }) "certificates" else null },
            { q: String -> if (listOf("관심사", "interest").any { q.contains(it) }) "interests" else null },
            { q: String -> if (listOf("기술", "스킬", "스택").any { q.contains(it) }) "skills" else null },
            { q: String -> if (listOf("학력", "학교", "대학").any { q.contains(it) }) "education" else null },
            { q: String -> if (listOf("mbti", "성격").any { q.contains(it) }) "mbti" else null },
            { q: String -> if (listOf("취미", "여가시간").any { q.contains(it) }) "hobbies" else null }
        )

        // 특정 프로젝트 제목이 포함되었는지 확인
        val matchedProject = resume.projects.find { userPrompt.contains(it.title) }
        if (matchedProject != null) {
            logger.info("Topic detected: Specific Project ('${matchedProject.title}').")
            val projectId = "project_${matchedProject.title.replace(Regex("\\s+"), "_")}"
            return resumePersistencePort.findContentById(projectId)?.let { listOf(it) } ?: emptyList()
        }

        // 규칙 리스트 순회
        for (rule in rules) {
            val chunkId = rule(normalizedQuery)
            if (chunkId != null) {
                logger.info("Topic detected by rule: $chunkId")
                // 'projects' 또는 'experiences'와 같이 복수형 컨텍스트 처리
                if (chunkId == "projects") {
                    return resume.projects.mapNotNull { proj -> resumePersistencePort.findContentById("project_${proj.title.replace(Regex("\\s+"), "_")}") }
                }
                if (chunkId == "experiences") {
                    return resume.experiences.mapNotNull { exp -> resumePersistencePort.findContentById("experience_${exp.company.replace(" ", "_")}") }
                }
                // 단일 컨텍스트 처리
                return resumePersistencePort.findContentById(chunkId)?.let { listOf(it) } ?: emptyList()
            }
        }

        // 규칙에 매칭되지 않으면 벡터 검색 수행
        logger.info("No specific rules matched. Performing general vector search as a fallback.")
        return resumePersistencePort.searchSimilarSections(userPrompt, topK = 3)
    }

    private fun classifyQueryType(userPrompt: String): QueryType {
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()
        val resumeKeywords = listOf(
            "경력", "이력", "회사", "프로젝트", "스킬", "기술", "학력", "mbti", "취미", "이력서",
            resumeProviderPort.getResume().name.lowercase()
        )
        return if (resumeKeywords.any { normalizedQuery.contains(it) } || resumeProviderPort.getResume().projects.any { userPrompt.contains(it.title) }) {
            QueryType.RESUME_RAG
        } else {
            QueryType.NON_RAG
        }
    }

    private fun createApiHistoryWindow(domainHistory: ChatHistory): List<ChatMessage> {
        var currentTokens = 0
        val recentMessages = mutableListOf<ChatMessage>()
        for (msg in domainHistory.history.reversed()) {
            val estimatedTokens = estimateTokens(msg.text)
            if (currentTokens + estimatedTokens > API_WINDOW_TOKENS) { break }
            recentMessages.add(msg)
            currentTokens += estimatedTokens
        }
        recentMessages.reverse()
        return recentMessages
    }

    private suspend fun summarizeHistoryIfNeeded(originalHistory: ChatHistory): ChatHistory {
        val totalTokens = originalHistory.history.sumOf { estimateTokens(it.text) }
        if (totalTokens < SUMMARY_TRIGGER_TOKENS || originalHistory.history.size < SUMMARY_SOURCE_MESSAGES + 2) {
            return originalHistory
        }

        logger.info("History for user {} exceeds token threshold. Starting summarization.", originalHistory.userId)
        val messagesToSummarize = originalHistory.history.take(SUMMARY_SOURCE_MESSAGES)
        val recentMessages = originalHistory.history.drop(SUMMARY_SOURCE_MESSAGES)
        val summarizationPrompt = """다음 대화의 핵심 내용을 한두 문단으로 간결하게 요약해주세요. --- ${messagesToSummarize.joinToString("\n") { "[${it.role}]: ${it.text}" }} --- 요약:""".trimIndent()
        val summaryText = geminiPort.summerizeContent(summarizationPrompt)

        if (summaryText.isBlank()) {
            logger.warn("Summarization failed or returned empty. Skipping history modification.")
            return originalHistory
        }
        val summaryMessage = ChatMessage(role = "model", text = "이전 대화 요약: $summaryText")
        val newMessages = mutableListOf(summaryMessage).apply { addAll(recentMessages) }
        return ChatHistory(originalHistory.userId, newMessages)
    }

    private fun estimateTokens(text: String): Int = (text.length * 1.5).toInt()
}