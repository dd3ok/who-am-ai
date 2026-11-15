package com.dd3ok.whoamai.domain

import com.fasterxml.jackson.annotation.JsonInclude

enum class ChatEventType { SOURCES, DELTA, DONE, ERROR }

/** Sources are the documents supplied to the model, not a claim of sentence-level verification. */
data class ChatSource(val id: String, val title: String, val url: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatEvent(
    val type: ChatEventType,
    val content: String? = null,
    val sources: List<ChatSource>? = null,
    val requestId: String? = null,
    val code: String? = null
)
