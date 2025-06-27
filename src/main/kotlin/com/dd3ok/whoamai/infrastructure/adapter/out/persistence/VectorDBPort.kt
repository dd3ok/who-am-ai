package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

interface VectorDBPort {
    suspend fun indexResume(sections: Map<String, String>): Int
    suspend fun searchSimilarResumeSections(query: String, topK: Int): List<String>
}