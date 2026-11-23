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
import org.springframework.core.io.ClassPathResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
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
        assertTrue(result.first().contains("Google Gemini 3.8 Flash"))
        assertTrue(result.first().contains("Gemini 임베딩"))
    }

    @Test
    fun `public github project query should return portfolio projects rather than only service profile`() = runTest {
        persistencePort.stub(ChunkIdGenerator.forProject("정산 플랫폼"), "PROJECT_CONTENT")
        val result = contextRetriever.retrieveByRule("최근 공개 GitHub 프로젝트 알려줘")

        assertEquals(listOf("PROJECT_CONTENT"), result)
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

    @Test
    fun `project aliases and parenthetical codes match rule retrieval even without project keyword`() = runTest {
        val biss = Project(
            title = "삼성전자로지텍 설치지원시스템(BISS) 개편",
            company = "미라콤아이앤씨"
        )
        val bqms = Project(
            title = "삼성전자 입찰견적관리시스템(BQMS) – MRO 몰/2.0",
            aliases = listOf("MRO 몰"),
            company = "미라콤아이앤씨"
        )
        val provider = object : ResumeProviderPort {
            override fun getResume() = resume.copy(projects = listOf(biss, bqms))
            override fun isInitialized() = true
        }
        val bissId = ChunkIdGenerator.forProject(biss.title)
        val bqmsId = ChunkIdGenerator.forProject(bqms.title)
        persistencePort.stub(bissId, "BISS_CONTENT")
        persistencePort.stub(bqmsId, "BQMS_CONTENT")

        val retriever = ContextRetriever(persistencePort, provider, ownDomainProfileProvider, true)
        assertEquals(
            setOf(bissId, bqmsId),
            retriever.retrieveSourcesByRule("삼성전자 BQMS와 BISS 개발·운영 경험을 알려주세요").map { it.chunkId }.toSet()
        )
        assertEquals(
            listOf(bqmsId),
            retriever.retrieveSourcesByRule("MRO 몰 개발 경험 알려줘").map { it.chunkId }
        )
    }

    @Test
    fun `company vector retrieval retains all company projects instead of capping at four chunks`() = runTest {
        val companyProjects = (1..7).map { index ->
            Project(
                title = "지마켓 프로젝트 $index",
                company = "지마켓",
                skills = listOf("Spring Boot")
            )
        }
        val provider = object : ResumeProviderPort {
            override fun getResume() = resume.copy(projects = companyProjects)
            override fun isInitialized() = true
        }
        val expId = ChunkIdGenerator.forExperience("지마켓")
        persistencePort.stub(expId, "지마켓 경력")
        companyProjects.forEach { project ->
            persistencePort.stub(ChunkIdGenerator.forProject(project.title), "${project.title} 백엔드 작업")
        }

        val retriever = ContextRetriever(persistencePort, provider, ownDomainProfileProvider, true)
        val result = retriever.retrieveSourcesByVector(
            "지마켓에서 어떤 백엔드 작업을 했어?",
            RouteDecision(queryType = QueryType.RESUME_RAG, company = "지마켓")
        )

        assertEquals(8, result.size)
        assertTrue(result.any { it.chunkId == expId })
        companyProjects.forEach { project ->
            assertTrue(result.any { it.chunkId == ChunkIdGenerator.forProject(project.title) })
        }
    }

    @Test
    fun `portfolioCatalog exposes sections service profile and salient project body keywords`() {
        val richResume = resume.copy(
            projects = listOf(
                Project(
                    title = "ESM 로그인 개편",
                    company = "지마켓",
                    skills = listOf("Spring Boot", "Redis"),
                    tags = listOf("보안", "로그인"),
                    description = "글로벌 접근 제어와 세션 통합",
                    problem = "해외 부정 로그인 탐지 필요",
                    contribution = "GeoIP 기반 접속 지역 차단 및 부분 취소 정산 연계"
                ),
                Project(
                    title = "실시간 이벤트 파이프라인",
                    company = "지마켓",
                    skills = listOf("Kafka"),
                    contribution = "Debezium CDC 기반 변경 데이터 캡처 파이프라인 구축"
                )
            )
        )
        val retriever = ContextRetriever(
            persistencePort,
            object : ResumeProviderPort {
                override fun getResume(): Resume = richResume
                override fun isInitialized(): Boolean = true
            },
            ownDomainProfileProvider,
            metadataFilterEnabled = true
        )

        val catalog = retriever.portfolioCatalog()
        val loginEntry = catalog.first { it.chunkId == ChunkIdGenerator.forProject("ESM 로그인 개편") }
        val cdcEntry = catalog.first { it.chunkId == ChunkIdGenerator.forProject("실시간 이벤트 파이프라인") }

        assertTrue(catalog.any { it.chunkId == "service_profile" })
        assertTrue(catalog.any { it.chunkId == ChunkIdGenerator.forRecentActivities() })
        assertTrue(loginEntry.keywords.any { it.equals("GeoIP", ignoreCase = true) })
        assertTrue(loginEntry.summary.contains("부분 취소"))
        assertTrue(cdcEntry.keywords.any { it.equals("Debezium", ignoreCase = true) })
    }

    @Test
    fun `retrieveSourcesByIds preserves order and resolves service_profile alongside project chunks`() = runTest {
        val projectId = ChunkIdGenerator.forProject("정산 플랫폼")
        persistencePort.stub(projectId, "정산 플랫폼 내용")

        val results = contextRetriever.retrieveSourcesByIds(listOf("service_profile", projectId, "service_profile"))

        assertEquals(listOf("service_profile", projectId), results.map { it.chunkId })
        assertEquals("정산 플랫폼", results[1].title)
    }

    @Test
    fun `real resume catalog retains tail keywords in recent_activities and project bodies`() {
        val realResume: Resume = ClassPathResource("resume.json").inputStream.use {
            jacksonObjectMapper().readValue(it)
        }
        val retriever = ContextRetriever(
            persistencePort,
            object : ResumeProviderPort {
                override fun getResume(): Resume = realResume
                override fun isInitialized(): Boolean = true
            },
            ownDomainProfileProvider,
            metadataFilterEnabled = true
        )

        val catalog = retriever.portfolioCatalog()
        val recentEntry = catalog.first { it.chunkId == ChunkIdGenerator.forRecentActivities() }
        val sagaEntry = catalog.first { it.chunkId == ChunkIdGenerator.forProject("commerce-saga") }

        for (expectedKeyword in listOf("isitnowbot", "market-data-skills", "yfinance", "repoclip", "WATCHLIST.md", "boring-backend")) {
            assertTrue(
                recentEntry.keywords.any { it.equals(expectedKeyword, ignoreCase = true) },
                "Expected recent_activities catalog entry to retain keyword '$expectedKeyword', got: ${recentEntry.keywords}"
            )
        }
        assertTrue(sagaEntry.keywords.any { it.equals("Debezium", ignoreCase = true) })
    }

    @Test
    fun `chat artifact and DB meta questions return service_profile including jamo split typos`() = runTest {
        for (question in listOf(
            "이 질문들은 DB에서 가져오나요?",
            "현재 대화 내용들은 DB를ㅈ ㅗ회하나요?",
            "추천 질문은 어디서 가져와?"
        )) {
            val sources = contextRetriever.retrieveSourcesByRule(question)
            assertEquals(listOf("service_profile"), sources.map { it.chunkId }, question)
            val content = sources.single().content
            assertTrue(content.contains("chat_answer_cache"), question)
            assertTrue(content.contains("chat_histories"), question)
            assertTrue(content.contains("resume_chunks_embedding2"), question)
        }
    }

    @Test
    fun `explicit non-host project in chat data question takes precedence over service_profile`() = runTest {
        val savepoint = Project(title = "savepoint", company = "개인 프로젝트")
        val savepointId = ChunkIdGenerator.forProject("savepoint")
        persistencePort.stub(savepointId, "SAVEPOINT_CONTENT")
        val retriever = ContextRetriever(
            persistencePort,
            object : ResumeProviderPort {
                override fun getResume() = resume.copy(projects = resume.projects + savepoint)
                override fun isInitialized() = true
            },
            ownDomainProfileProvider,
            metadataFilterEnabled = true
        )

        val sources = retriever.retrieveSourcesByRule("savepoint는 대화 내용을 DB에 저장하나요?")

        assertEquals(listOf(savepointId), sources.map { it.chunkId })
    }

    @Test
    fun `conversationTopics skips previous project for service meta question and keeps service_profile for follow ups`() {
        val skillsProject = Project(title = "spring-engineering-skills", company = "개인 프로젝트")
        val retriever = ContextRetriever(
            persistencePort,
            object : ResumeProviderPort {
                override fun getResume() = resume.copy(projects = resume.projects + skillsProject)
                override fun isInitialized() = true
            },
            ownDomainProfileProvider,
            metadataFilterEnabled = true
        )

        assertTrue(
            retriever.conversationTopics(
                "현재 대화 내용들은 DB를ㅈ ㅗ회하나요?",
                listOf(com.dd3ok.whoamai.domain.ChatMessage("user", "spring-engineering-skills는 어떤 프로젝트인가요?"))
            ).isEmpty()
        )

        val topics = retriever.conversationTopics(
            "그럼 TTL은 몇 일이야?",
            listOf(
                com.dd3ok.whoamai.domain.ChatMessage("user", "spring-engineering-skills는 어떤 프로젝트인가요?"),
                com.dd3ok.whoamai.domain.ChatMessage("user", "이 질문들은 DB에서 가져오나요?")
            )
        )
        assertTrue(topics.any { it.title == "who-am-ai 구현 정보" && it.sourceIds == listOf("service_profile") })
    }

    @Test
    fun `service_profile catalog entry exposes Korean chat and DB keywords`() {
        val serviceEntry = contextRetriever.portfolioCatalog().first { it.chunkId == "service_profile" }
        for (keyword in listOf("현재 서비스", "대화 이력", "답변 캐시", "DB 조회", "MongoDB")) {
            assertTrue(
                serviceEntry.keywords.any { it.equals(keyword, ignoreCase = true) },
                "Expected service_profile keyword '$keyword', got: ${serviceEntry.keywords}"
            )
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
