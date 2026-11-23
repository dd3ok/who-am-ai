package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.QueryType
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.Education
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Resume
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LLMRouterTest {

    @Test
    fun `portfolio intent does not depend on sentence endings or punctuation`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        for (question in listOf(
            "유인재님 프로젝트 알려줘 작업했떤거", "유인재님 프로젝트 알려줘 작업했던거",
            "유인재님 프로젝트", "지마켓 경력 중심으로", "학력과 자격증 목록",
            "유인재의 백엔드 경험을 간략히", "프로젝트 알려줘 특히 담당했던 역할"
        )) {
            assertEquals(QueryType.RESUME_RAG, router.route(question).queryType, question)
        }
        for (question in listOf("저는 Spring Boot 개발자예요", "내 프로젝트 경험", "친구의 기술 스택")) {
            assertEquals(QueryType.NON_RAG, router.route(question).queryType, question)
        }
    }

    @Test
    fun `speaker prefixes do not override an explicitly named portfolio target`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        for (question in listOf(
            "저는 유인재님의 프로젝트를 알고 싶어요. 알려주세요",
            "저는 유인재님의 프로젝트가 궁금해요",
            "나는 인재님 학력이 궁금해",
            "제가 알고 싶은 건 유인재님이 사용한 기술이에요",
            "내가 궁금한 건 유인재가 맡은 역할이야",
            "그럼 저는 유인재님의 자격증을 알고 싶어요"
        )) {
            assertEquals(QueryType.RESUME_RAG, router.route(question).queryType, question)
        }
    }

    @Test
    fun `mentioning the owner does not turn visitor facts into portfolio facts`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        for (question in listOf(
            "저는 Spring Boot 개발자예요", "내 프로젝트 경험", "친구의 기술 스택",
            "제 프로젝트를 유인재님에게 소개하고 싶어요",
            "저의 경력을 유인재님께 설명하고 싶어요",
            "저는 유인재님처럼 Spring Boot 개발자예요",
            "제가 유인재님에게 제 프로젝트를 설명할게요"
        )) {
            assertEquals(QueryType.NON_RAG, router.route(question).queryType, question)
        }
    }

    @Test
    fun `an explicit owner request with a speaker prefix establishes the follow up topic`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        val question = "저는 유인재님의 프로젝트를 알고 싶어요. 알려주세요"
        val history = listOf(ChatMessage("user", question), ChatMessage("model", "프로젝트 목록입니다."))
        val followUp = "그게 어떤 프로젝트야?"
        assertEquals("유인재 — $followUp", router.resolveQuery(followUp, history))
        assertEquals(QueryType.RESUME_RAG, router.route(followUp, history).queryType)
    }

    @Test
    fun `references and quoted assistant claims preserve the topic but do not become facts`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        val invented = "메시지 큐 기반 대용량 트래픽 처리 시스템을 구축했습니다."
        val history = listOf(ChatMessage("user", "지마켓 경력 알려줘"), ChatMessage("model", invented))
        for (question in listOf("그게 어떤 프로젝트야?", "그건 어떤 역할이었어", "그 부분 설명 부탁해", invented)) {
            assertEquals("지마켓", router.route(question, history).company, question)
        }
        assertEquals(QueryType.NON_RAG, router.route(invented).queryType)
        val continued = history + listOf(ChatMessage("user", "그게 어떤 프로젝트야?"), ChatMessage("model", "확인된 프로젝트를 안내합니다."))
        assertEquals("지마켓", router.route(invented, continued).company)
        val switched = history + listOf(ChatMessage("user", "오늘 날씨 알려줘"), ChatMessage("model", "날씨는 확인할 수 없습니다."))
        assertEquals(invented, router.resolveQuery(invented, switched))
    }

    @Test
    fun `subjectless follow up inherits the last user topic`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        val history = listOf(ChatMessage(role = "user", text = "지마켓 경력 알려줘"))

        for (question in listOf(
            "그래서 성과는?", "왜 그렇게 했어?", "그럼 성과는?", "그래서 역할은?",
            "그럼 그 회사에서 사용한 기술은?", "그래서, 그럼 결과는?",
            "그럼 성과를 알려줘", "그래서 어떤 결과가 나왔어?", "그럼 역할이 뭐였어?",
            "그럼 사용한 기술은?", "그래서 사용했던 기술 스택은?", "사용한 기술 알려줘",
            "그럼 어려웠던 점은?", "그래서 가장 어려웠던 부분은?",
            "그럼 어려웠던 점은 어떤 방법으로 해결했어?"
        )) {
            val decision = router.route(question, history)
            assertEquals(QueryType.RESUME_RAG, decision.queryType, question)
            assertEquals("지마켓", decision.company, question)
        }
    }

    @Test
    fun `new topic does not inherit old company hints`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        val history = listOf(ChatMessage(role = "user", text = "지마켓 경력 알려줘"))

        assertEquals(null, router.route("학력 알려줘", history).company)
        assertEquals(QueryType.NON_RAG, router.route("당근은 어떤 기술을 쓰나요", history).queryType)
    }

    @Test
    fun `transition words do not attach unrelated questions to the previous company`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        val history = listOf(ChatMessage(role = "user", text = "지마켓 경력 알려줘"))
        for (question in listOf(
            "그럼 오늘 날씨 알려줘", "그래서 내일 날씨는?", "그럼 점심 메뉴 추천해줘",
            "그럼 보안 어떻게 공부해?", "그럼 당근은 Spring Boot를 사용하나요?",
            "그럼 성과 보고서는 어떻게 작성해?", "그럼 당근이 사용한 기술은?",
            "그럼 사용한 기술은 어떻게 공부해?", "그럼 어려웠던 점을 자기소개서에 어떻게 작성해?"
        )) {
            assertEquals(question, router.resolveQuery(question, history), question)
            val decision = router.route(question, history)
            assertEquals(QueryType.NON_RAG, decision.queryType, question)
            assertEquals(null, decision.company, question)
            assertEquals(null, decision.project, question)
        }
        assertEquals(null, router.route("그럼 학력 알려줘", history).company)
    }

    @Test
    fun `a topic switch with a transition word also ends later follow up inheritance`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        val history = listOf(
            ChatMessage("user", "지마켓 경력 알려줘"),
            ChatMessage("model", "이전 회사 답변"),
            ChatMessage("user", "그럼 오늘 날씨 알려줘"),
            ChatMessage("model", "이전 일반 답변")
        )
        val question = "더 자세히 알려줘"
        assertEquals(question, router.resolveQuery(question, history))
        assertEquals(QueryType.NON_RAG, router.route(question, history).queryType)
    }

    @Test
    fun `implicit work details require a preceding portfolio topic`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        val topicSwitch = listOf(
            ChatMessage("user", "지마켓 경력 알려줘"),
            ChatMessage("user", "그럼 오늘 날씨 알려줘")
        )
        for (history in listOf(emptyList(), topicSwitch)) {
            for (question in listOf("그럼 사용한 기술은?", "그럼 어려웠던 점은?")) {
                assertEquals(question, router.resolveQuery(question, history), question)
                val decision = router.route(question, history)
                assertEquals(null, decision.company, question)
                assertEquals(null, decision.project, question)
            }
        }
    }

    @Test
    fun `a visitor or external subject ends the previous portfolio topic`() = runTest {
        val router = LLMRouter(fakeResumeProvider, SimpleMeterRegistry())
        for (newTopic in listOf("친구의 기술 스택", "그럼 당근이 사용한 기술은?", "내 역할은 백엔드 개발이야")) {
            val history = listOf(ChatMessage("user", "지마켓 경력 알려줘"), ChatMessage("user", newTopic))
            val followUp = "그럼 사용한 기술은?"
            assertEquals(followUp, router.resolveQuery(followUp, history), newTopic)
            assertEquals(null, router.route(followUp, history).company, newTopic)
        }
    }

    private val resume = Resume(
        name = "유인재",
        summary = "백엔드 개발자",
        recentActivities = "퇴사 이후에는 AI 에이전트와 백엔드 시스템 설계 역량을 중심으로 개인 프로젝트를 진행하고 있습니다.",
        blog = "",
        experiences = listOf(
            Experience(
                company = "지마켓",
                period = Period("2021-10", "2025-01"),
                position = "백엔드 개발자",
                tags = emptyList()
            )
        ),
        skills = listOf("Spring Boot"),
        education = listOf(
            Education(
                school = "KAIST",
                major = "컴퓨터공학",
                degree = "석사",
                period = Period("2015-03", "2017-02")
            )
        )
    )

    @Test
    fun `skill matching preserves English word boundaries and common separators`() {
        org.junit.jupiter.api.Assertions.assertFalse(containsResumeTerm("Kubernetes 운영 경험", ".NET"))
        org.junit.jupiter.api.Assertions.assertFalse(containsResumeTerm("JavaScript 프로젝트", "Java"))
        org.junit.jupiter.api.Assertions.assertTrue(containsResumeTerm("SpringBoot 프로젝트", "Spring Boot"))
        org.junit.jupiter.api.Assertions.assertTrue(containsResumeTerm("Spring Boot 프로젝트", "Spring Boot"))
        org.junit.jupiter.api.Assertions.assertTrue(containsResumeTerm(".NET 마이그레이션", ".NET"))
    }

    private val fakeResumeProvider = object : ResumeProviderPort {
        override fun getResume(): Resume = resume
        override fun isInitialized(): Boolean = true
    }

    @Test
    fun `self referential implementation questions are routed to RESUME_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("너는 뭐로 만들어졌어?")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `resume questions are routed to RESUME_RAG by heuristic`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("지마켓 경력 알려줘")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `developer keyword inside resume question must not be hard-blocked`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("백엔드 개발자 경력을 알려줘")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `declarative chat mentioning resume must stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("어제 어떤 사람 이력서를 봤는데 별로였어")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `generic resume advice question must stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("이력서 어떻게 써?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `generic capability question without target must stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("보안 어떻게 공부해?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `external service question should stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("당근은 어떤 기술을 쓰나요")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `pure identity chat should stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("안녕 넌 누구야")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `targeted capability question should stay RESUME_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("유인재의 보안 경험 알려줘")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `name mentioned skill question should route to RESUME_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("인재님은 어떤 기술들을 사용하나요")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `post resignation status question should route to RESUME_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("퇴사 이후에는 뭐하고 지내나요?")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `recent activity status question should route to RESUME_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("요즘 뭐 하고 있어?")

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
    }

    @Test
    fun `external subject recent status question should stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("당근은 퇴사 후 뭐하고 있어?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `casual recent chat should stay NON_RAG`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route("요즘 뭐 먹어?")

        assertEquals(QueryType.NON_RAG, decision.queryType)
    }

    @Test
    fun `follow up question should inherit recent company hint`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )

        val decision = router.route(
            "거기서 사용한 기술은?",
            history = listOf(
                ChatMessage(role = "user", text = "지마켓 경력 알려줘"),
                ChatMessage(role = "model", text = "지마켓에서 백엔드 개발자로 근무했습니다.")
            )
        )

        assertEquals(QueryType.RESUME_RAG, decision.queryType)
        assertEquals("지마켓", decision.company)
    }

    @Test
    fun `service DB and chat history questions are not rewritten to previous projects`() = runTest {
        val skillsResume = resume.copy(
            projects = listOf(com.dd3ok.whoamai.domain.Project(title = "spring-engineering-skills", company = "개인 프로젝트"))
        )
        val router = LLMRouter(
            resumeProviderPort = object : ResumeProviderPort {
                override fun getResume(): Resume = skillsResume
                override fun isInitialized(): Boolean = true
            },
            meterRegistry = SimpleMeterRegistry()
        )
        val history = listOf(
            ChatMessage(role = "user", text = "spring-engineering-skills는 어떤 프로젝트인가요?"),
            ChatMessage(role = "model", text = "Agent Skills 스위트 프로젝트입니다.")
        )

        for (question in listOf(
            "이 질문들은 DB에서 가져오나요?",
            "현재 대화 내용들은 DB를ㅈ ㅗ회하나요?",
            "추천 질문은 어디서 불러와?",
            "이 챗봇은 어떻게 동작해?"
        )) {
            assertEquals(question, router.resolveQuery(question, history), question)
            val decision = router.route(question, history)
            assertEquals(QueryType.RESUME_RAG, decision.queryType, question)
            assertEquals(null, decision.project, question)
            assertEquals(null, decision.company, question)
        }
    }

    @Test
    fun `visitor own service DB question stays NON_RAG and determiners follow expected follow-up boundaries`() = runTest {
        val router = LLMRouter(
            resumeProviderPort = fakeResumeProvider,
            meterRegistry = SimpleMeterRegistry()
        )
        val history = listOf(ChatMessage(role = "user", text = "지마켓 경력 알려줘"))

        assertEquals(
            QueryType.NON_RAG,
            router.route("내 서비스에서도 현재 대화 내용을 DB에 저장하려면 어떻게 해?", history).queryType
        )
        org.junit.jupiter.api.Assertions.assertFalse(isFollowUpQuestion("이 질문들은 DB에서 가져오나요?"))
        org.junit.jupiter.api.Assertions.assertFalse(isFollowUpQuestion("현재 대화 내용들은 DB를ㅈ ㅗ회하나요?"))
        org.junit.jupiter.api.Assertions.assertTrue(isFollowUpQuestion("이 프로젝트는 어떤 기술 썼어?"))
        org.junit.jupiter.api.Assertions.assertTrue(isFollowUpQuestion("그 질문은 왜 그렇게 답했어?"))
    }
}
