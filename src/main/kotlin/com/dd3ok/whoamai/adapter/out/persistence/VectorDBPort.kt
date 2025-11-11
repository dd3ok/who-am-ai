package com.dd3ok.whoamai.adapter.out.persistence

import org.springframework.ai.vectorstore.filter.Filter

interface VectorDBPort {
    suspend fun indexResume(chunks: List<ResumeChunk>): Int

    suspend fun findChunksByIds(ids: Collection<String>): Map<String, String>

    suspend fun searchSimilarResumeSections(
        query: String,
        topK: Int,
        filter: Filter.Expression? = null
    ): List<String>
    suspend fun findChunkById(id: String): String?
}
