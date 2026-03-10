package com.dd3ok.whoamai.application.service
	
import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.domain.Resume
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Project
import com.dd3ok.whoamai.domain.Period
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.Filter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextRetrieverTest {

    private val resume = Resume(
        name = "유인재",
        summary = "요약",
        interests = listOf("AI 서비스", "MSA"),
        skills = listOf("Spring Boot"),
        experiences = listOf(
            Experience(
                company = "지마켓",
                aliases = listOf("G마켓"),
                period = Period("2021-10", "2025-01"),
                position = "백엔드 개발자"
            )
        ),
        projects = listOf(
            Project(
                title = "정산 플랫폼",
                company = "지마켓",
                period = Period("2024-01", "2024-12"),
                skills = listOf("Spring Boot")
            )
        )
    )

    private val persistencePort = FakeResumePersistencePort()
    private val ownDomainProfileProvider = OwnDomainProfileProvider()
    private val providerPort = object : ResumeProviderPort {
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    private val contextRetriever = ContextRetriever(
        persistencePort,
        providerPort,
        ownDomainProfileProvider,
        metadataFilterEnabled = true
    )

    @BeforeEach
    fun setup() {
        persistencePort.clear()
    }

    @Test
    fun `partial name questions return summary chunk`() = runTest {
        val expected = "SUMMARY"
        persistencePort.stub(ChunkIdGenerator.forSummary(), expected)

        val result = contextRetriever.retrieveByRule("인재님에 대해 알려주세요")

        assertEquals(1, result.size)
        assertTrue(result.first().contains(expected))
    }

    @Test
    fun `candidate intro questions are not mistaken for model identity questions`() = runTest {
        val expected = "SUMMARY"
        persistencePort.stub(ChunkIdGenerator.forSummary(), expected)

        val result = contextRetriever.retrieveByRule("유인재는 누구야?")

        assertEquals(1, result.size)
        assertTrue(result.first().contains(expected))
    }

    @Test
    fun `interest style questions return interest chunk`() = runTest {
        val expected = "INTERESTS"
        persistencePort.stub(ChunkIdGenerator.forInterests(), expected)

        val result = contextRetriever.retrieveByRule("관심있는게 뭐야?")

        assertEquals(1, result.size)
        assertTrue(result.first().contains(expected))
    }

    @Test
    fun `name mentioned skill questions should return skills chunk before summary`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forSummary(), "SUMMARY")
        persistencePort.stub(ChunkIdGenerator.forSkills(), "SKILLS")

        val result = contextRetriever.retrieveByRule("인재님은 어떤 기술들을 사용하나요")

        assertEquals(1, result.size)
        assertTrue(result.first().contains("SKILLS"))
    }

    @Test
    fun `own service implementation question should return own domain context`() = runTest {
        val result = contextRetriever.retrieveByRule("who-am-ai는 어떤 기술로 만들었나요")

        assertEquals(1, result.size)
        assertTrue(result.first().contains("Spring AI"))
        assertTrue(result.first().contains("MongoDB Atlas Vector Search"))
    }

    @Test
    fun `self referential implementation question should return own domain context`() = runTest {
        val result = contextRetriever.retrieveByRule("너는 뭐로 만들어졌어?")

        assertEquals(1, result.size)
        assertTrue(result.first().contains("Google Gemini"))
    }

    @Test
    fun `declarative chat with resume keyword must not trigger rule retrieval`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forSummary(), "SUMMARY")

        val result = contextRetriever.retrieveByRule("어제 어떤 사람 이력서를 봤는데 별로였어")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `company mentioned experience question should defer broad rule to vector`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forExperience("지마켓"), "GMARKET")

        val result = contextRetriever.retrieveByRule("지마켓 경력 알려줘")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `skill mentioned project question should defer broad rule to vector`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forProject("정산 플랫폼"), "PROJECT")

        val result = contextRetriever.retrieveByRule("Spring Boot 프로젝트 소개해줘")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `project oriented vector query should prefer project chunk`() = runTest {
        persistencePort.vectorResults = listOf(
            ResumeSearchResult(
                chunkId = ChunkIdGenerator.forExperience("지마켓"),
                chunkType = "experience",
                content = "지마켓에서 백엔드 개발자로 근무했습니다. 주요 업무는 플랫폼 운영입니다."
            ),
            ResumeSearchResult(
                chunkId = ChunkIdGenerator.forProject("정산 플랫폼"),
                chunkType = "project",
                content = "정산 플랫폼 프로젝트에서 품질 개선과 아키텍처 개선을 진행했습니다."
            )
        )

        val result = contextRetriever.retrieveByVector(
            "품질 개선 프로젝트 알려줘",
            RouteDecision(queryType = QueryType.RESUME_RAG)
        )

        assertEquals(2, result.size)
        assertTrue(result.first().contains("프로젝트"))
    }

    @Test
    fun `metadata filter should be attached only when route hints exist`() = runTest {
        contextRetriever.retrieveByVector(
            "지마켓 경력 알려줘",
            RouteDecision(queryType = QueryType.RESUME_RAG, company = "지마켓")
        )
        assertTrue(persistencePort.lastFilter != null)

        persistencePort.lastFilter = null

        contextRetriever.retrieveByVector(
            "학력 알려줘",
            RouteDecision(queryType = QueryType.RESUME_RAG)
        )
        assertTrue(persistencePort.lastFilter == null)
    }

    @Test
    fun `retrieval profile should change by query type`() = runTest {
        contextRetriever.retrieveByVector(
            "지마켓 경력 알려줘",
            RouteDecision(queryType = QueryType.RESUME_RAG, company = "지마켓")
        )
        assertEquals(4, persistencePort.lastTopK)
        assertEquals(0.72, persistencePort.lastSimilarityThreshold)

        contextRetriever.retrieveByVector(
            "학력 알려줘",
            RouteDecision(queryType = QueryType.RESUME_RAG)
        )
        assertEquals(3, persistencePort.lastTopK)
        assertEquals(0.70, persistencePort.lastSimilarityThreshold)

        contextRetriever.retrieveByVector(
            "유인재의 협업 강점 알려줘",
            RouteDecision(queryType = QueryType.RESUME_RAG)
        )
        assertEquals(8, persistencePort.lastTopK)
        assertEquals(0.58, persistencePort.lastSimilarityThreshold)
    }

    private class FakeResumePersistencePort : ResumePersistencePort {
        private val contents = mutableMapOf<String, String>()
        var vectorResults: List<ResumeSearchResult> = emptyList()
        var lastFilter: Filter.Expression? = null
        var lastTopK: Int? = null
        var lastSimilarityThreshold: Double? = null

        fun stub(id: String, content: String) {
            contents[id] = content
        }

        fun clear() = contents.clear()

        override suspend fun index(resume: Resume): Int = 0

        override suspend fun findContentById(id: String): String? = contents[id]
        override suspend fun findContentsByIds(ids: List<String>): Map<String, String> =
            ids.mapNotNull { id -> contents[id]?.let { content -> id to content } }.toMap()

        override suspend fun searchSimilarSections(
            query: String,
            topK: Int,
            filter: Filter.Expression?,
            similarityThreshold: Double?
        ): List<ResumeSearchResult> {
            lastFilter = filter
            lastTopK = topK
            lastSimilarityThreshold = similarityThreshold
            return vectorResults
        }
    }
}
