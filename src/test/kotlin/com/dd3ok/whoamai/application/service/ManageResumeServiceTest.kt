package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.domain.Resume
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import kotlin.math.max
import kotlin.test.assertEquals

class ManageResumeServiceTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `reindex requests are serialized in a single application instance`() = runTest {
        val persistencePort = RecordingResumePersistencePort()
        val service = ManageResumeService(
            resumeProviderPort = InitializedResumeProviderPort,
            resumePersistencePort = persistencePort
        )

        val first = async { service.reindexResumeData() }
        runCurrent()
        val second = async { service.reindexResumeData() }
        runCurrent()
        advanceUntilIdle()

        first.await()
        second.await()
        assertEquals(2, persistencePort.calls)
        assertEquals(1, persistencePort.maxActiveCalls)
    }

    private object InitializedResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(name = "tester")
        override fun isInitialized(): Boolean = true
    }

    private class RecordingResumePersistencePort : ResumePersistencePort {
        var calls = 0
        var maxActiveCalls = 0
        private var activeCalls = 0

        override suspend fun index(resume: Resume): Int {
            calls += 1
            activeCalls += 1
            maxActiveCalls = max(maxActiveCalls, activeCalls)
            delay(100)
            activeCalls -= 1
            return 1
        }

        override suspend fun findContentById(id: String): String? = null
        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> = emptyMap()
        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> = emptyList()
    }
}
