package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.ChatMessage

fun interface ConversationTopicPort {
    /** Select an existing user-established topic, or null for an independent/unclear question. */
    suspend fun selectTopic(question: String, history: List<ChatMessage>, topics: List<String>): String?
}
