package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ManageResumeService(
    private val resumeProviderPort: ResumeProviderPort,
    private val resumePersistencePort: ResumePersistencePort
) : ManageResumeUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val reindexMutex = Mutex()

    override suspend fun reindexResumeData(): String = reindexMutex.withLock {
        if (!resumeProviderPort.isInitialized()) {
            val errorMessage = "Resume data is not loaded. Cannot perform re-indexing."
            logger.error(errorMessage)
            return@withLock errorMessage
        }

        val indexedCount = resumePersistencePort.index(resumeProviderPort.getResume())

        val successMessage = "Resume indexing process finished. Indexed $indexedCount documents."
        logger.info(successMessage)
        successMessage
    }
}
