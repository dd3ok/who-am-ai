package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.Resume
import org.bson.Document

interface ResumePersistencePort {
    suspend fun index(resume: Resume): Int

    suspend fun findContentById(id: String): String?

    suspend fun searchSimilarSections(query: String, topK: Int, filter: Document? = null): List<String>
}