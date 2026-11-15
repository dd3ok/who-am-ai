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
        recentActivities = "퇴사 이후에는 AI 에이전트와 백엔드 시스템 설계 역량을 중심으로 개인 프로젝트를 진행하고 있습니다.",
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
    fun `compound profile question retrieves both requested sections`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forEducation(), "EDUCATION")
        persistencePort.stub(ChunkIdGenerator.forCertificates(), "CERTIFICATES")

        assertEquals(
            setOf("EDUCATION", "CERTIFICATES"),
            contextRetriever.retrieveByRule("학력과 자격증 알려줘").toSet()
        )
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
    fun `post resignation status questions return recent activities chunk`() = runTest {
        val expected = "RECENT_ACTIVITIES"
        persistencePort.stub(ChunkIdGenerator.forRecentActivities(), expected)

        val result = contextRetriever.retrieveByRule("퇴사 이후에는 뭐하고 지내나요?")

        assertEquals(1, result.size)
        assertTrue(result.first().contains(expected))
    }

    @Test
    fun `recent activity status questions return recent activities chunk`() = runTest {
        val expected = "RECENT_ACTIVITIES"
        persistencePort.stub(ChunkIdGenerator.forRecentActivities(), expected)

        val result = contextRetriever.retrieveByRule("요즘 뭐 하고 있어?")

        assertEquals(1, result.size)
        assertTrue(result.first().contains(expected))
    }

    @Test
    fun `casual recent chat does not return recent activities chunk`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forRecentActivities(), "RECENT_ACTIVITIES")

        val result = contextRetriever.retrieveByRule("요즘 뭐 먹어?")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `external subject recent status question does not return recent activities chunk`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forRecentActivities(), "RECENT_ACTIVITIES")

        val result = contextRetriever.retrieveByRule("당근은 퇴사 후 뭐하고 있어?")

        assertTrue(result.isEmpty())
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
        assertTrue(result.first().contains("100milionseek"))
        assertTrue(result.first().contains("Gemini 임베딩"))
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

    @Test
    fun `vector retrieval returns empty context when persistence storage is unavailable`() = runTest {
        persistencePort.failSearch = true

        val result = contextRetriever.retrieveByVector(
            "hello",
            RouteDecision(queryType = QueryType.RESUME_RAG)
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `benchmark override reaches vector search for every retrieval profile`() = runTest {
        val retriever = ContextRetriever(persistencePort, providerPort, ownDomainProfileProvider, true, 0.81)
        retriever.retrieveByVector("지마켓 경력 알려줘", RouteDecision(QueryType.RESUME_RAG, company = "지마켓"))
        assertEquals(0.81, persistencePort.lastSimilarityThreshold)
        retriever.retrieveByVector("유인재의 협업 강점 알려줘", RouteDecision(QueryType.RESUME_RAG))
        assertEquals(0.81, persistencePort.lastSimilarityThreshold)
    }

    @Test
    fun `sources preserve stable project id and readable title`() = runTest {
        val projectId = ChunkIdGenerator.forProject("정산 플랫폼")
        persistencePort.stub(projectId, "프로젝트 본문")
        val source = contextRetriever.retrieveSourcesByRule("정산 플랫폼 설명해줘").single()
        assertEquals(projectId, source.chunkId)
        assertEquals("정산 플랫폼", source.title)
        assertEquals("프로젝트 본문", source.content)
        assertEquals(null, source.url)
    }

    @Test
    fun `source links are limited to registered public http urls`() = runTest {
        val projectId = ChunkIdGenerator.forProject("정산 플랫폼")
        persistencePort.stub(projectId, "본문")
        for ((url, expected) in listOf(
            "https://example.com/project" to "https://example.com/project",
            "javascript:alert(1)" to null,
            "https://user:password@example.com/project" to null,
            "not a URL" to null
        )) {
            val provider = object : ResumeProviderPort {
                override fun getResume() = resume.copy(projects = listOf(resume.projects.single().copy(sourceUrl = url)))
                override fun isInitialized() = true
            }
            val retriever = ContextRetriever(persistencePort, provider, ownDomainProfileProvider, true)
            assertEquals(expected, retriever.retrieveSourcesByRule("정산 플랫폼 설명해줘").single().url)
        }
    }

    private class FakeResumePersistencePort : ResumePersistencePort {
        private val contents = mutableMapOf<String, String>()
        var vectorResults: List<ResumeSearchResult> = emptyList()
        var lastFilter: Filter.Expression? = null
        var lastTopK: Int? = null
        var lastSimilarityThreshold: Double? = null
        var failSearch: Boolean = false

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
            if (failSearch) {
                throw IllegalStateException("vector storage unavailable")
            }
            return vectorResults
        }
    }
}
