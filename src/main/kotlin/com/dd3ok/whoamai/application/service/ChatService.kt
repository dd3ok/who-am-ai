package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.ChatHistory
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
    private val resumeProviderPort: ResumeProviderPort
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
        domainHistory.history.forEach { msg -> logger.info("  - Role: ${msg.role}, Text: ${msg.text.take(50)}...") }

        val pastApiHistory = createApiHistoryWindow(domainHistory)

        val queryType = classifyQueryType(userPrompt)

        val finalApiHistory = if (queryType == QueryType.RESUME_RAG) {
            logger.info("Query classified as RESUME_RAG. Retrieving context...")
            // RAG 의도일 때만 컨텍스트를 검색합니다.
            val relevantContexts = retrieveContextsForRagQuery(userPrompt)
            createRagPrompt(pastApiHistory, userPrompt, relevantContexts)
        } else {
            logger.info("Query classified as NON_RAG. Proceeding with conversational prompt.")
            // 일반 대화 의도일 때는 컨텍스트 검색을 아예 실행하지 않습니다.
            createConversationalPrompt(pastApiHistory, userPrompt)
        }

        val modelResponseBuilder = StringBuilder()
        return geminiPort.generateChatContent(finalApiHistory)
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
     * [RAG 트랙] 컨텍스트가 있을 때 사용하는 엄격한 프롬프트.
     * 사실 기반 답변과 환각 방지에 초점을 맞춘다.
     */
    private fun createRagPrompt(history: List<ChatMessage>, userPrompt: String, contexts: List<String>): List<ChatMessage> {
        val contextString = contexts.joinToString("\n---\n")

        val finalUserPrompt = """
            # Behavioral Protocol: Resume Expertise
            ## Execution Rules
            1.  **Perspective**: Answer from a third-person perspective (e.g., "He has...").
            2.  **Data Source**: Base your answer STRICTLY and ONLY on the 'Context' section.
            3.  **Out-of-Scope**: If the 'Context' lacks the info, reply ONLY with: '그 부분에 대한 정보는 없습니다.'

            ---
            # Context
            $contextString

            ---
            # User's Question
            $userPrompt
        """.trimIndent()

        return history + ChatMessage(role = "user", text = finalUserPrompt)
    }

    /**
     * [일반 대화 트랙]
     */
    private fun createConversationalPrompt(history: List<ChatMessage>, userPrompt: String): List<ChatMessage> {
        val finalUserPrompt = """
        # Behavioral Protocol: General Conversation
        
        ## Primary Goal for THIS turn
        Your most important task is to understand the user's LATEST message and respond to it directly and naturally.
        - **If the user is introducing themselves, your ONLY goal is to acknowledge their name warmly.**
        - **If the user asks a question, answer it.**
        - **If the user is making small talk, respond in kind.**
        
        ## General Rules
        - Act as a friendly and helpful AI assistant named '인재 AI'.
        - Remember details from the conversation history to maintain context.
        - You MUST NOT mention '유인재' or his resume unless the user asks about him.

        ---
        # User's LATEST Message
        $userPrompt
    """.trimIndent()

        return history + ChatMessage(role = "user", text = finalUserPrompt)
    }

    private suspend fun retrieveContextsForRagQuery(userPrompt: String): List<String> {
        val resume = resumeProviderPort.getResume()
        val normalizedQuery = userPrompt.replace(Regex("\\s+"), "").lowercase()
        if (listOf("누구야", "누구세요", "소개", "너는누구", "자기소개", resume.name.lowercase()).any { normalizedQuery.contains(it) }) {
            return resumePersistencePort.findContentById("summary")?.let { listOf(it) } ?: emptyList()
        }
        val matchedProject = resume.projects.find { proj -> userPrompt.contains(proj.title) }
        if (matchedProject != null) {
            logger.info("Topic detected: Specific Project ('${matchedProject.title}').")
            val projectId = "project_${matchedProject.title.replace(Regex("\\s+"), "_")}"
            return resumePersistencePort.findContentById(projectId)?.let { listOf(it) } ?: emptyList()
        }
        if (listOf("프로젝트", "project", "수행과제").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: General Projects.")
            return resume.projects.mapNotNull { proj -> resumePersistencePort.findContentById("project_${proj.title.replace(Regex("\\s+"), "_")}") }
        }
        if (listOf("총 경력", "총경력", "전체경력").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: Total Experience.")
            return resumePersistencePort.findContentById("experience_total_summary")?.let { listOf(it) } ?: emptyList()
        }
        if (listOf("경력", "이력", "회사").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: General Experience.")
            return resume.experiences.mapNotNull { exp -> resumePersistencePort.findContentById("experience_${exp.company.replace(" ", "_")}") }
        }
        if (listOf("자격증", "certificate", "sqld", "정보처리기사").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: Certificates.")
            return resumePersistencePort.findContentById("certificates")?.let { listOf(it) } ?: emptyList()
        }
        if (listOf("관심사", "관심", "interest").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: Interests.")
            return resumePersistencePort.findContentById("interests")?.let { listOf(it) } ?: emptyList()
        }
        if (listOf("기술", "스킬", "스택").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: Skills.")
            return resumePersistencePort.findContentById("skills")?.let { listOf(it) } ?: emptyList()
        }
        if (listOf("학력", "학교", "전공", "대학", "대학교").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: Education.")
            return resumePersistencePort.findContentById("education")?.let { listOf(it) } ?: emptyList()
        }
        if (listOf("mbti", "성격").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: MBTI.")
            return resumePersistencePort.findContentById("mbti")?.let { listOf(it) } ?: emptyList()
        }
        if (listOf("취미", "쉴때", "여가시간").any { normalizedQuery.contains(it) }) {
            logger.info("Topic detected: Hobbies.")
            return resumePersistencePort.findContentById("hobbies")?.let { listOf(it) } ?: emptyList()
        }

        // 최종 단계: 벡터 검색
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