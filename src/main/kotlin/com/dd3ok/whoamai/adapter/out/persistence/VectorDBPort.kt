package com.dd3ok.whoamai.adapter.out.persistence

import org.bson.Document

interface VectorDBPort {
    suspend fun indexResume(chunks: List<ResumeChunk>): Int
    suspend fun searchSimilarResumeSections(query: String, topK: Int, filter: Document? = null): List<String>
    suspend fun findChunkById(id: String): String?
}