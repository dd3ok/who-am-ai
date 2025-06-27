package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

data class ResumeChunk(
    val id: String,
    val type: String,
    val content: String,
    val company: String? = null,
    val skills: List<String> = emptyList(),
    val source: Map<String, Any>
)
