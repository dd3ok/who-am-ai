package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.domain.ChatHistory
import org.springframework.stereotype.Repository

@Repository
class ChatHistoryRepositoryAdapter(
    private val documentRepository: ChatHistoryDocumentRepository
) : ChatHistoryRepository {

    private fun ChatHistoryDocument.toDomain() = ChatHistory(this.userId, this.messages)

    private fun ChatHistory.toEntity() = ChatHistoryDocument(this.userId, this.history.toMutableList())

    override suspend fun findByUserId(userId: String): ChatHistory? {
        return documentRepository.findById(userId)?.toDomain()
    }

    override suspend fun save(chatHistory: ChatHistory): ChatHistory {
        val entity = chatHistory.toEntity()
        return documentRepository.save(entity).toDomain()
    }
}