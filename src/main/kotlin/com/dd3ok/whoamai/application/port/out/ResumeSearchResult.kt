package com.dd3ok.whoamai.application.port.out

data class ResumeSearchResult(
    val chunkId: String,
    val chunkType: String,
    val content: String,
    val title: String = chunkId,
    val url: String? = null
)
