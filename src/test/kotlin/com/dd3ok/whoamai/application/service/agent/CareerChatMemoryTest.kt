package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import kotlin.test.assertEquals

class CareerChatMemoryTest {

    @Test
    fun `memory reads persisted chat history as ai messages`() {
        val repository = InMemoryChatHistoryRepository()
        repository.seed(
            "user-1",
            ChatHistory(
                userId = "user-1",
                messages = listOf(
                    ChatMessage(role = "user", text = "질문"),
                    ChatMessage(role = "model", text = "답변")
                )
            )
        )
        val memory = CareerChatMemory(repository)

        val messages = memory.get("user-1")

        assertEquals(2, messages.size)
        assertEquals("질문", messages[0].text)
        assertEquals("답변", messages[1].text)
    }

    @Test
    fun `memory appends ai messages to persisted history`() {
        val repository = InMemoryChatHistoryRepository()
        repository.seed("user-1", ChatHistory("user-1", listOf(ChatMessage(role = "user", text = "이전"))))
        val memory = CareerChatMemory(repository)

        memory.add("user-1", listOf(UserMessage("질문"), AssistantMessage("답변")))

        val savedHistory = repository.findByUserIdBlocking("user-1") ?: error("history not saved")
        assertEquals(listOf("user", "user", "model"), savedHistory.history.map { it.role })
        assertEquals(listOf("이전", "질문", "답변"), savedHistory.history.map { it.text })
    }

    @Test
    fun `memory clear removes persisted messages`() {
        val repository = InMemoryChatHistoryRepository()
        repository.seed("user-1", ChatHistory("user-1", listOf(ChatMessage(role = "user", text = "질문"))))
        val memory = CareerChatMemory(repository)

        memory.clear("user-1")

        val savedHistory = repository.findByUserIdBlocking("user-1") ?: error("history not saved")
        assertEquals(emptyList(), savedHistory.history)
    }

    private class InMemoryChatHistoryRepository : ChatHistoryRepository {
        private val storage = mutableMapOf<String, ChatHistory>()

        fun seed(userId: String, history: ChatHistory) {
            storage[userId] = history
        }

        fun findByUserIdBlocking(userId: String): ChatHistory? = storage[userId]

        override suspend fun findByUserId(userId: String): ChatHistory? = storage[userId]

        override suspend fun save(chatHistory: ChatHistory): ChatHistory {
            storage[chatHistory.userId] = chatHistory
            return chatHistory
        }
    }
}
