package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

import com.dd3ok.whoamai.domain.ChatMessage
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "chat_histories")
data class ChatHistoryDocument(
    @Id
    val userId: String,
    val messages: MutableList<ChatMessage> = mutableListOf()
)