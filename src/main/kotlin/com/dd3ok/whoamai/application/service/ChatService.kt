package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.StreamMessage
import com.dd3ok.whoamai.infrastructure.adapter.out.persistence.VectorDBPort
import com.google.genai.types.Content
import com.google.genai.types.Part
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

@Service
class ChatService(
    private val geminiPort: GeminiPort,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val vectorDBPort: VectorDBPort
) : ChatUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val API_WINDOW_TOKENS = 2048
        private const val SUMMARY_TRIGGER_TOKENS = 4096
        private const val SUMMARY_SOURCE_MESSAGES = 5
        private const val RESUME_SEARCH_TOP_K = 3 // RAG 검색 시 가져올 컨텍스트 개수
    }

    @PostConstruct
    fun setupResumeIndex() {
        logger.info("Starting resume indexing process...")
        val resumeSections = parseResume()
        if (resumeSections.isNotEmpty()) {
            val indexedCount = runBlocking {
                vectorDBPort.indexResume(resumeSections)
            }
            logger.info("Resume indexing process finished. Indexed $indexedCount documents.")
        } else {
            logger.warn("No resume sections found to index.")
        }
    }

    override suspend fun streamChatResponse(message: StreamMessage): Flow<String> {
        val userId = message.uuid
        val userPrompt = message.content

        val relevantContexts = vectorDBPort.searchSimilarResumeSections(userPrompt, topK = RESUME_SEARCH_TOP_K)
        val apiHistory: List<Content>

        if (relevantContexts.isNotEmpty()) {
            logger.info("Found ${relevantContexts.size} relevant contexts from VectorDB. Creating augmented prompt.")
            apiHistory = createVectorSearchAugmentedPrompt(userPrompt, relevantContexts)
        } else {
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
            당신은 주어진 "관련 이력서 정보"를 바탕으로 채용 담당자의 질문에 답변하는 AI 어시스턴트입니다.
            반드시 제공된 정보만을 사용하여 답변해야 합니다. 정보에 없는 내용은 "이력서에 기재된 내용으로는 답변하기 어렵습니다."라고 솔직하게 답변하세요.
            답변은 항상 한국어로, 전문적이고 친절한 어조를 유지해주세요.

            --- 관련 이력서 정보 ---
            $contextString
            --- 정보 끝 ---

            채용 담당자 질문: $userPrompt
        """.trimIndent()
        return listOf(Content.fromParts(Part.fromText(augmentedPrompt)))
    }

    private fun parseResume(): Map<String, String> {
        try {
            val resource = ClassPathResource("resume.md")
            val rawContent = resource.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            val sections = mutableMapOf<String, String>()
            val parts = rawContent.split(Regex("(?=### )")).filter { it.isNotBlank() }

            for (part in parts) {
                val lines = part.lines()
                val category = lines.first().removePrefix("### ").trim()
                val content = lines.drop(1).joinToString("\n").trim()

                if (category.isNotEmpty() && content.isNotEmpty()) {
                    sections[category.replace(Regex("\\s|/"), "_")] = content
                }
            }
            logger.info("Resume parsed into ${sections.size} sections: ${sections.keys}")
            return sections
        } catch (e: Exception) {
            logger.error("Failed to load and parse resume.md. Please check if the file exists in src/main/resources.", e)
            return emptyMap()
        }
    }

    private fun createApiHistoryWindow(domainHistory: ChatHistory): List<Content> {
        var currentTokens = 0
        val recentMessages = mutableListOf<ChatMessage>()

        for (msg in domainHistory.history.reversed()) {
            val estimatedTokens = estimateTokens(msg.text)
            if (currentTokens + estimatedTokens > API_WINDOW_TOKENS) {
                break
            }
            recentMessages.add(msg)
            currentTokens += estimatedTokens
        }
        recentMessages.reverse()

        return recentMessages.map { msg ->
            Content.builder()
                .role(msg.role)
                .parts(listOf(Part.fromText(msg.text)))
                .build()
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

        val summarizationPrompt = """
            다음 대화는 챗봇의 이전 대화 기록입니다. 이 대화의 핵심 내용을 다른 AI 모델이 대화의 맥락을 이어갈 수 있도록 한두 문단으로 간결하게 요약해주세요.
            ---
            ${messagesToSummarize.joinToString("\n") { "[${it.role}]: ${it.text}" }}
            ---
            요약:
        """.trimIndent()

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
        return text.length * 2
    }
}