package com.dd3ok.whoamai.domain

import com.dd3ok.whoamai.infrastructure.adapter.out.persistence.ChatHistoryDocument

class ChatHistory(
    val userId: String,
    messages: List<ChatMessage> = emptyList()
) {
    private val _history: MutableList<ChatMessage> = messages.toMutableList()

    val history: List<ChatMessage> get() = _history.toList()

    fun addMessage(msg: ChatMessage) {
        _history.add(msg)
    }

    fun toEntity() = ChatHistoryDocument(userId, _history)
}