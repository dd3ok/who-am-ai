package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.domain.ChatHistory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository

@Repository
class ChatHistoryRepositoryAdapter(
    private val documentRepository: ChatHistoryDocumentRepository,
    @Value("\${spring.data.mongodb.uri:}") private val mongoUri: String
) : ChatHistoryRepository {

    private val logger = LoggerFactory.getLogger(javaClass)

    private fun ChatHistoryDocument.toDomain() = ChatHistory(this.userId, this.messages)

    private fun ChatHistory.toEntity() = ChatHistoryDocument(this.userId, this.history.toMutableList())

    override suspend fun findByUserId(userId: String): ChatHistory? {
        if (mongoUri.isBlank()) {
            logger.debug("MongoDB URI is empty. Chat history lookup is disabled.")
            return null
        }
        return documentRepository.findById(userId)?.toDomain()
    }

    override suspend fun save(chatHistory: ChatHistory): ChatHistory {
        if (mongoUri.isBlank()) {
            logger.debug("MongoDB URI is empty. Chat history save is disabled.")
            return chatHistory
        }
        val entity = chatHistory.toEntity()
        return documentRepository.save(entity).toDomain()
    }
}
