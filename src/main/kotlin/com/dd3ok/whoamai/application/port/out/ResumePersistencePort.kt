package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.Resume
import org.springframework.ai.vectorstore.filter.Filter

interface ResumePersistencePort {
    suspend fun index(resume: Resume): Int

    suspend fun findContentById(id: String): String?

    suspend fun findContentsByIds(ids: Collection<String>): Map<String, String>

    suspend fun searchSimilarSections(
        query: String,
        topK: Int,
        filter: Filter.Expression? = null
    ): List<String>
}
