package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.Resume

interface ResumePersistencePort {
    suspend fun index(resume: Resume): Int

    suspend fun findContentById(id: String): String?

    suspend fun searchSimilarSections(query: String, topK: Int): List<String>
}