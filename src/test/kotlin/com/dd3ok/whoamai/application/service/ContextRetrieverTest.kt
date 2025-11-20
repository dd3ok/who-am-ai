package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.domain.Resume
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import kotlin.test.assertEquals

class ContextRetrieverTest {

    private val resume = Resume(
        name = "유인재",
        summary = "요약",
        interests = listOf("AI 서비스", "MSA")
    )

    private val persistencePort = FakeResumePersistencePort()
    private val providerPort = object : ResumeProviderPort {
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    private val contextRetriever = ContextRetriever(persistencePort, providerPort)

    @BeforeEach
    fun setup() {
        persistencePort.clear()
    }

    @Test
    fun `partial name questions return summary chunk`() = runTest {
        val expected = "SUMMARY"
        persistencePort.stub(ChunkIdGenerator.forSummary(), expected)

        val result = contextRetriever.retrieveByRule("인재님에 대해 알려주세요")

        assertEquals(listOf(expected), result)
    }

    @Test
    fun `interest style questions return interest chunk`() = runTest {
        val expected = "INTERESTS"
        persistencePort.stub(ChunkIdGenerator.forInterests(), expected)

        val result = contextRetriever.retrieveByRule("관심있는게 뭐야?")

        assertEquals(listOf(expected), result)
    }

    private class FakeResumePersistencePort : ResumePersistencePort {
        private val contents = mutableMapOf<String, String>()

        fun stub(id: String, content: String) {
            contents[id] = content
        }

        fun clear() = contents.clear()

        override suspend fun index(resume: Resume): Int = 0

        override suspend fun findContentById(id: String): String? = contents[id]

        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?
        ): List<String> = emptyList()
    }
}
