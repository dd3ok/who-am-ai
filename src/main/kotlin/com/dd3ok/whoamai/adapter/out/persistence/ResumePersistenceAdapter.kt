package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.service.ResumeChunkingService
import com.dd3ok.whoamai.domain.Resume
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ResumePersistenceAdapter(
    private val vectorDBPort: VectorDBPort,
    private val resumeChunkingService: ResumeChunkingService
) : ResumePersistencePort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun index(resume: Resume): Int {
        logger.info("Delegating resume chunking to ResumeChunkingService.")
        val chunks = resumeChunkingService.generateChunks(resume)

        if (chunks.isEmpty()) {
            logger.warn("No resume chunks were generated. Indexing skipped.")
            return 0
        }

        return vectorDBPort.indexResume(chunks)
    }

    override suspend fun findContentById(id: String): String? {
        return vectorDBPort.findChunkById(id)
    }

    override suspend fun searchSimilarSections(query: String, topK: Int, filter: Document?): List<String> {
        return vectorDBPort.searchSimilarResumeSections(query, topK, filter)
    }
}