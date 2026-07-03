package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatHistoryWindow
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

@Repository
class ChatHistoryRepositoryAdapter(
    private val documentRepository: ChatHistoryDocumentRepository,
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
    @Value("\${spring.mongodb.uri:}") private val mongoUri: String
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

    override suspend fun appendMessages(userId: String, messages: List<ChatMessage>, maxTokens: Int): ChatHistory {
        if (mongoUri.isBlank()) {
            logger.debug("MongoDB URI is empty. Chat history append is disabled.")
            return ChatHistoryWindow.trim(userId, messages, maxTokens)
        }

        repeat(APPEND_MAX_ATTEMPTS - 1) { attempt ->
            try {
                return appendMessagesOnce(userId, messages, maxTokens)
            } catch (e: DuplicateKeyException) {
                logger.debug("Chat history append insert raced for user {}. Retrying.", userId)
                delay(retryBackoffMs(attempt))
            } catch (e: ChatHistoryAppendConflictException) {
                logger.debug("Chat history append revision conflict for user {}. Retrying.", userId)
                delay(retryBackoffMs(attempt))
            }
        }
        return appendMessagesOnce(userId, messages, maxTokens)
    }

    private suspend fun appendMessagesOnce(
        userId: String,
        messages: List<ChatMessage>,
        maxTokens: Int
    ): ChatHistory {
        val current = documentRepository.findById(userId)
        val trimmed = ChatHistoryWindow.trim(
            userId = userId,
            messages = current?.messages.orEmpty() + messages,
            maxTokens = maxTokens
        )

        if (current == null) {
            val inserted = ChatHistoryDocument(
                userId = userId,
                messages = trimmed.history.toMutableList(),
                revision = 1
            )
            return reactiveMongoTemplate.insert(inserted).awaitSingle().toDomain()
        }

        val query = Query(
            Criteria.where("_id").`is`(userId)
                .andOperator(currentRevisionCriteria(current.revision))
        )
        val update = Update()
            .set("messages", trimmed.history)
            .set("revision", current.revision + 1)

        val result = reactiveMongoTemplate.updateFirst(query, update, ChatHistoryDocument::class.java)
            .awaitSingle()
        if (result.modifiedCount != 1L) {
            throw ChatHistoryAppendConflictException()
        }
        return trimmed
    }

    private fun currentRevisionCriteria(revision: Long): Criteria {
        return if (revision == 0L) {
            Criteria().orOperator(
                Criteria.where("revision").`is`(0L),
                Criteria.where("revision").exists(false)
            )
        } else {
            Criteria.where("revision").`is`(revision)
        }
    }

    private fun retryBackoffMs(attempt: Int): Long = 25L * (attempt + 1)

    private class ChatHistoryAppendConflictException : RuntimeException()

    private companion object {
        const val APPEND_MAX_ATTEMPTS = 3
    }
}
