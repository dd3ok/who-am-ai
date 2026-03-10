package com.dd3ok.whoamai.adapter.out.persistence
	
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import org.springframework.ai.vectorstore.filter.Filter
	
interface VectorDBPort {
    suspend fun indexResume(chunks: List<ResumeChunk>): Int
    suspend fun searchSimilarResumeSections(
        query: String,
        topK: Int,
        filter: Filter.Expression? = null,
        similarityThreshold: Double? = null
    ): List<ResumeSearchResult>
    suspend fun findChunkById(id: String): String?
    suspend fun findChunksByIds(ids: List<String>): Map<String, String>
}
