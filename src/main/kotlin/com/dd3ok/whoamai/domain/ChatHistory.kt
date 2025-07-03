package com.dd3ok.whoamai.domain

class ChatHistory(
    val userId: String,
    messages: List<ChatMessage> = emptyList()
) {
    private val _history: MutableList<ChatMessage> = messages.toMutableList()

    val history: List<ChatMessage> get() = _history.toList()

    fun addMessage(msg: ChatMessage) {
        _history.add(msg)
    }
}