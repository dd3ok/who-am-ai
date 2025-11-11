package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort // <-- 올바른 포트를 import 합니다.
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ManageResumeService(
    private val resumeProviderPort: ResumeProviderPort,
    private val resumePersistencePort: ResumePersistencePort
) : ManageResumeUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun reindexResumeData(): String {
        val latestResume = try {
            resumeProviderPort.reload()
        } catch (ex: Exception) {
            val errorMessage = "Failed to reload resume data before re-indexing: ${ex.message}"
            logger.error(errorMessage, ex)
            return errorMessage
        }

        if (latestResume.name.isBlank()) {
            val errorMessage = "Resume data is empty after reload. Cannot perform re-indexing."
            logger.error(errorMessage)
            return errorMessage
        }

        val indexedCount = resumePersistencePort.index(latestResume)
        val successMessage = "Resume indexing process finished. Indexed $indexedCount documents."
        logger.info(successMessage)
        return successMessage
    }
}
