package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.application.port.out.ChatHistoryRepository
import com.dd3ok.whoamai.domain.ChatHistory
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.runBlocking
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.stereotype.Component

@Component
class CareerChatMemory(
    private val chatHistoryRepository: ChatHistoryRepository
) : ChatMemory {
    override fun add(conversationId: String, messages: List<Message>) {
        runBlocking {
            val current = chatHistoryRepository.findByUserId(conversationId) ?: ChatHistory(conversationId)
            chatHistoryRepository.save(
                ChatHistory(
                    userId = conversationId,
                    messages = current.history + messages.map(::toDomainMessage)
                )
            )
        }
    }

    override fun get(conversationId: String): List<Message> = runBlocking {
        chatHistoryRepository.findByUserId(conversationId)
            ?.history
            .orEmpty()
            .map(::toAiMessage)
    }

    override fun clear(conversationId: String) {
        runBlocking {
            chatHistoryRepository.save(ChatHistory(userId = conversationId, messages = emptyList()))
        }
    }

    private fun toAiMessage(message: ChatMessage): Message =
        when (message.role.lowercase()) {
            "system" -> SystemMessage(message.text)
            "model", "assistant" -> AssistantMessage(message.text)
            else -> UserMessage(message.text)
        }

    private fun toDomainMessage(message: Message): ChatMessage =
        when (message) {
            is SystemMessage -> ChatMessage(role = "system", text = message.text)
            is AssistantMessage -> ChatMessage(role = "model", text = message.text.orEmpty())
            else -> ChatMessage(role = "user", text = message.text)
        }
}
