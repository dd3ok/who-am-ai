package com.dd3ok.whoamai.adapter.out.persistence

data class ResumeChunk(
    val id: String,
    val type: String,
    val content: String,
    val company: String? = null,
    val skills: List<String>? = null,
    val source: Map<String, Any>
)