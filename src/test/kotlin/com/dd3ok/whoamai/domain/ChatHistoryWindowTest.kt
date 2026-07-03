package com.dd3ok.whoamai.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryWindowTest {

    @Test
    fun `api history window can be empty when the newest message exceeds the token budget`() {
        val messages = listOf(ChatMessage(role = "user", text = "abcdef"))

        val window = ChatHistoryWindow.recentMessages(messages, maxTokens = 5)

        assertTrue(window.isEmpty())
    }

    @Test
    fun `persistence trim keeps at least the newest message when it exceeds the token budget`() {
        val messages = listOf(ChatMessage(role = "user", text = "abcdef"))

        val history = ChatHistoryWindow.trim(userId = "user-1", messages = messages, maxTokens = 5)

        assertEquals(messages, history.history)
    }
}
