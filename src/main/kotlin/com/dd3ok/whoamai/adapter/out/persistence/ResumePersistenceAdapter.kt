package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.service.ResumeChunkingService
import com.dd3ok.whoamai.domain.Resume
import org.slf4j.LoggerFactory
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.stereotype.Component

/**
 * 작업 목적: VectorDBPort를 통해 이력서를 색인하고, 규칙·벡터 검색 요청을 위임한다.
 * 주요 로직: Resume을 Chunk로 분할한 후 Vector Store에 저장하고, 필터 표현식 기반 검색을 수행한다.
 */
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

    override suspend fun searchSimilarSections(
        query: String,
        topK: Int,
        filter: Filter.Expression?
    ): List<String> {
        return vectorDBPort.searchSimilarResumeSections(query, topK, filter)
    }
}
