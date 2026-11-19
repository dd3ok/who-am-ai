package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.ConversationTopic
import com.dd3ok.whoamai.application.port.out.PortfolioCatalogEntry
import com.dd3ok.whoamai.application.port.out.PortfolioSelection
import com.dd3ok.whoamai.application.port.out.PortfolioSelectionIntent
import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.config.GeminiDecisionProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(20)
class GeminiDecisionAdapterTest {

    private val mapper = jacksonObjectMapper()
    private val asciiKeyRegex = Regex("^[A-Za-z0-9_-]{1,64}$")

    @Test
    fun `selectTopic sends choice question to v1beta decisions and resolves selected topic`() = runBlocking {
        val loginTopic = ConversationTopic("ESM 로그인 개편", listOf("project_ESM_로그인_개편"))
        val sellerTopic = ConversationTopic("ESM 셀러딜", listOf("project_ESM_셀러딜"))
        val responseJson = """
            {
              "answers": {
                "topic": {
                  "type": "choice",
                  "choice": "t_1",
                  "probabilities": {"none": 0.04, "t_1": 0.92, "t_2": 0.04},
                  "confidence": 0.92
                }
              },
              "usage": {
                "total_input_tokens": 142,
                "total_output_tokens": 0,
                "total_tokens": 142
              }
            }
        """.trimIndent()

        withServer(listOf(HttpStubResponse(200, "application/json", responseJson))) { baseUrl, requests ->
            val meterRegistry = SimpleMeterRegistry()
            val adapter = adapter(baseUrl, meterRegistry = meterRegistry)
            val result = adapter.selectTopic(
                question = "얼마나 빨라졌어?",
                history = listOf(ChatMessage("user", "ESM 로그인 개편 설명해줘")),
                topics = listOf(loginTopic, sellerTopic)
            )

            assertEquals(loginTopic, result)
            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1beta/decisions", request.path)
            assertEquals("decision-test-key", request.apiKeyHeader)
            assertTrue(request.contentType.orEmpty().startsWith("application/json"))
            assertFalse(request.body.contains("decision-test-key"))

            val body = mapper.readTree(request.body)
            assertEquals("gemini-decision-1", body["model"].asString())
            val topicQuestion = body["questions"]["topic"]
            assertEquals("choice", topicQuestion["type"].asString())
            assertTrue(topicQuestion["instructions"].asString().isNotBlank())
            val criteria = topicQuestion["criteria"]
            assertEquals(setOf("none", "t_1", "t_2"), criteria.propertyNames().toSet())

            val parsedInput = mapper.readTree(body["input"].asString())
            assertEquals("얼마나 빨라졌어?", parsedInput["question"].asString())
            assertEquals("ESM 로그인 개편", parsedInput["topics"][0]["title"].asString())
            assertEquals(
                1.0,
                meterRegistry.find("whoamai.chat.gemini.decision.attempt.total")
                    .tag("operation", "topic").counter()?.count()
            )
            assertEquals(
                142.0,
                meterRegistry.find("whoamai.chat.gemini.decision.input_tokens")
                    .tag("operation", "topic").summary()?.totalAmount()
            )
        }
    }

    @Test
    fun `selectTopic returns null on none choice low confidence or empty candidates`() = runBlocking {
        val topic = ConversationTopic("ESM 로그인 개편", listOf("project_ESM_로그인_개편"))
        val noneResponse = """
            {
              "answers": {
                "topic": {
                  "type": "choice",
                  "choice": "none",
                  "probabilities": {"none": 0.91, "t_1": 0.09},
                  "confidence": 0.91
                }
              }
            }
        """.trimIndent()
        val lowConfidenceResponse = """
            {
              "answers": {
                "topic": {
                  "type": "choice",
                  "choice": "t_1",
                  "probabilities": {"none": 0.49, "t_1": 0.51},
                  "confidence": 0.51
                }
              }
            }
        """.trimIndent()

        withServer(
            listOf(
                HttpStubResponse(200, "application/json", noneResponse),
                HttpStubResponse(200, "application/json", lowConfidenceResponse)
            )
        ) { baseUrl, requests ->
            val adapter = adapter(baseUrl)
            assertNull(adapter.selectTopic("얼마나 빨라졌어?", emptyList(), emptyList()))
            assertEquals(0, requests.size)

            assertNull(adapter.selectTopic("오늘 날씨는?", emptyList(), listOf(topic)))
            assertNull(adapter.selectTopic("그거 뭐야?", emptyList(), listOf(topic)))
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun `selectTopic rejects malformed answers unknown choice keys and HTTP errors`() = runBlocking {
        val topic = ConversationTopic("ESM 로그인 개편", listOf("project_ESM_로그인_개편"))
        val badResponses = listOf(
            HttpStubResponse(200, "application/json", """{"answers":{}}"""),
            HttpStubResponse(200, "application/json", """{"answers":{"topic":{"type":"noul","noul":0.9,"confidence":0.9}}}"""),
            HttpStubResponse(200, "application/json", """{"answers":{"topic":{"type":"choice","choice":"t_99","confidence":0.9}}}"""),
            HttpStubResponse(200, "application/json", """{"answers":{"topic":{"type":"choice","choice":"t_1","confidence":1.2}}}"""),
            HttpStubResponse(200, "application/json", """not-json"""),
            HttpStubResponse(500, "application/json", """{"error":{"message":"internal error"}}""")
        )

        withServer(badResponses) { baseUrl, _ ->
            val meterRegistry = SimpleMeterRegistry()
            val adapter = adapter(baseUrl, meterRegistry = meterRegistry)
            repeat(badResponses.size) {
                assertFailsWith<Exception> {
                    adapter.selectTopic("얼마나 빨라졌어?", emptyList(), listOf(topic))
                }
            }
            assertEquals(
                1.0,
                meterRegistry.find("whoamai.chat.gemini.decision.error.total")
                    .tag("reason", "http_500").counter()?.count()
            )
        }
    }

    @Test
    fun `selectContext sends 1 choice and N ASCII noul questions for Korean chunkIds and ranks multi-chunk results`() = runBlocking {
        val catalog = listOf(
            PortfolioCatalogEntry("experience_지마켓", "experience", "지마켓 경력", "백엔드 엔지니어", listOf("Gmarket", "Java")),
            PortfolioCatalogEntry("project_ESM_로그인_개편", "project", "ESM 로그인 개편", "GeoIP 로그인 차단", listOf("GeoIP", "Redis")),
            PortfolioCatalogEntry("project_ESM_부정_로그인_탐지_ATO_도입", "project", "ATO 도입", "계정 도용 탐지", listOf("ATO", "GeoIP"))
        )
        val responseJson = """
            {
              "answers": {
                "intent": {
                  "type": "choice",
                  "choice": "PORTFOLIO_ANSWER",
                  "probabilities": {"PORTFOLIO_ANSWER": 0.96, "MISSING_EVIDENCE": 0.03, "GENERAL_CHAT": 0.01},
                  "confidence": 0.96
                },
                "c_0": {"type": "noul", "noul": 0.12, "confidence": 0.88},
                "c_1": {"type": "noul", "noul": 0.78, "confidence": 0.78},
                "c_2": {"type": "noul", "noul": 0.91, "confidence": 0.91}
              },
              "usage": {
                "total_input_tokens": 512,
                "total_output_tokens": 0,
                "total_tokens": 512
              }
            }
        """.trimIndent()

        withServer(listOf(HttpStubResponse(200, "application/json", responseJson))) { baseUrl, requests ->
            val adapter = adapter(baseUrl)
            val selection = adapter.selectContext(
                question = "GeoIP 기반 접속 차단은 어떻게 구현했나요?",
                history = listOf(ChatMessage("user", "지마켓 보안 작업 궁금해")),
                catalog = catalog
            )

            assertEquals(
                PortfolioSelection(
                    intent = PortfolioSelectionIntent.PORTFOLIO_ANSWER,
                    chunkIds = listOf("project_ESM_부정_로그인_탐지_ATO_도입", "project_ESM_로그인_개편")
                ),
                selection
            )

            val body = mapper.readTree(requests.single().body)
            val questions = body["questions"]
            val questionKeys = questions.propertyNames().toList()
            assertEquals(listOf("intent", "c_0", "c_1", "c_2"), questionKeys)
            assertTrue(questionKeys.all { asciiKeyRegex.matches(it) })
            assertEquals("choice", questions["intent"]["type"].asString())
            assertEquals(
                setOf("PORTFOLIO_ANSWER", "MISSING_EVIDENCE", "GENERAL_CHAT"),
                questions["intent"]["criteria"].propertyNames().toSet()
            )
            for (key in listOf("c_0", "c_1", "c_2")) {
                assertEquals("noul", questions[key]["type"].asString())
            }

            val parsedInput = mapper.readTree(body["input"].asString())
            assertEquals("c_0", parsedInput["catalog"][0]["id"].asString())
            assertEquals("GeoIP", parsedInput["catalog"][1]["keywords"][0].asString())
        }
    }

    @Test
    fun `selectContext applies fallback threshold demotion and high confidence rescue calibration rules`() = runBlocking {
        val catalog = listOf(
            PortfolioCatalogEntry("experience_지마켓", "experience", "지마켓 경력", "백엔드", listOf("Gmarket")),
            PortfolioCatalogEntry("project_ESM_로그인_개편", "project", "ESM 로그인 개편", "GeoIP", listOf("GeoIP"))
        )

        val fallbackPickResponse = """
            {
              "answers": {
                "intent": {"type": "choice", "choice": "PORTFOLIO_ANSWER", "confidence": 0.72},
                "c_0": {"type": "noul", "noul": 0.21, "confidence": 0.79},
                "c_1": {"type": "noul", "noul": 0.42, "confidence": 0.58}
              }
            }
        """.trimIndent()
        val demoteMissingResponse = """
            {
              "answers": {
                "intent": {"type": "choice", "choice": "PORTFOLIO_ANSWER", "confidence": 0.61},
                "c_0": {"type": "noul", "noul": 0.20, "confidence": 0.80},
                "c_1": {"type": "noul", "noul": 0.30, "confidence": 0.70}
              }
            }
        """.trimIndent()
        val rescueResponse = """
            {
              "answers": {
                "intent": {"type": "choice", "choice": "MISSING_EVIDENCE", "confidence": 0.55},
                "c_0": {"type": "noul", "noul": 0.10, "confidence": 0.90},
                "c_1": {"type": "noul", "noul": 0.89, "confidence": 0.89}
              }
            }
        """.trimIndent()
        val generalChatResponse = """
            {
              "answers": {
                "intent": {"type": "choice", "choice": "GENERAL_CHAT", "confidence": 0.95},
                "c_0": {"type": "noul", "noul": 0.80, "confidence": 0.80},
                "c_1": {"type": "noul", "noul": 0.15, "confidence": 0.85}
              }
            }
        """.trimIndent()

        withServer(
            listOf(
                HttpStubResponse(200, "application/json", fallbackPickResponse),
                HttpStubResponse(200, "application/json", demoteMissingResponse),
                HttpStubResponse(200, "application/json", rescueResponse),
                HttpStubResponse(200, "application/json", generalChatResponse)
            )
        ) { baseUrl, _ ->
            val adapter = adapter(baseUrl)

            assertEquals(
                PortfolioSelection(PortfolioSelectionIntent.PORTFOLIO_ANSWER, listOf("project_ESM_로그인_개편")),
                adapter.selectContext("ESM 로그인 관련 질문", emptyList(), catalog)
            )
            assertEquals(
                PortfolioSelection(PortfolioSelectionIntent.MISSING_EVIDENCE, emptyList()),
                adapter.selectContext("Kubernetes 운영 경험 알려줘", emptyList(), catalog)
            )
            assertEquals(
                PortfolioSelection(PortfolioSelectionIntent.PORTFOLIO_ANSWER, listOf("project_ESM_로그인_개편")),
                adapter.selectContext("MaxMind DB 연동은?", emptyList(), catalog)
            )
            assertEquals(
                PortfolioSelection(PortfolioSelectionIntent.GENERAL_CHAT, emptyList()),
                adapter.selectContext("오늘 날씨 어때?", emptyList(), catalog)
            )
        }
    }

    @Test
    fun `selectContext rejects inconsistent answers out-of-range probabilities and HTTP errors`() = runBlocking {
        val catalog = listOf(
            PortfolioCatalogEntry("recent_activities", "recent_activities", "최근 활동", "요약", emptyList())
        )
        val badResponses = listOf(
            HttpStubResponse(200, "application/json", """{"answers":{"intent":{"type":"choice","choice":"PORTFOLIO_ANSWER","confidence":0.9}}}"""),
            HttpStubResponse(200, "application/json", """{"answers":{"intent":{"type":"choice","choice":"UNKNOWN","confidence":0.9},"c_0":{"type":"noul","noul":0.5,"confidence":0.5}}}"""),
            HttpStubResponse(200, "application/json", """{"answers":{"intent":{"type":"choice","choice":"PORTFOLIO_ANSWER","confidence":0.9},"c_0":{"type":"choice","choice":"yes","confidence":0.9}}}"""),
            HttpStubResponse(200, "application/json", """{"answers":{"intent":{"type":"choice","choice":"PORTFOLIO_ANSWER","confidence":0.9},"c_0":{"type":"noul","noul":1.5,"confidence":0.9}}}"""),
            HttpStubResponse(429, "application/json", """{"error":{"message":"rate limit exceeded"}}""")
        )

        withServer(badResponses) { baseUrl, _ ->
            val meterRegistry = SimpleMeterRegistry()
            val adapter = adapter(baseUrl, meterRegistry = meterRegistry)
            repeat(badResponses.size) {
                assertFailsWith<Exception> {
                    adapter.selectContext("테스트 질문", emptyList(), catalog)
                }
            }
            assertEquals(
                1.0,
                meterRegistry.find("whoamai.chat.gemini.decision.error.total")
                    .tag("reason", "http_429").counter()?.count()
            )
        }
    }

    @Test
    fun `disabled decision properties delegate to fallback GeminiAdapter`() = runBlocking {
        val fallbackStreamingModel = object : StreamingChatModel {
            var callCount = 0
            private val responses = listOf(
                Flux.just(ChatResponse(listOf(Generation(AssistantMessage("""{"topicIndex":1}"""))))),
                Flux.just(ChatResponse(listOf(Generation(AssistantMessage("""{"intent":"GENERAL_CHAT","chunkIds":[]}""")))))
            )

            override fun stream(prompt: Prompt): Flux<ChatResponse> {
                val response = responses[callCount]
                callCount += 1
                return response
            }
        }
        val fallbackAdapter = GeminiAdapter(
            streamingChatModel = fallbackStreamingModel,
            chatModelProperties = GeminiChatModelProperties().apply { models = listOf("gemini-3.8-flash") },
            promptTemplateService = FakePromptProvider,
            meterRegistry = SimpleMeterRegistry()
        )
        val decisionAdapter = GeminiDecisionAdapter(
            decisionProperties = GeminiDecisionProperties(enabled = false, apiKey = "unused"),
            meterRegistry = SimpleMeterRegistry(),
            fallbackAdapter = fallbackAdapter
        )

        val topic = ConversationTopic("ESM 로그인 개편", listOf("project_ESM_로그인_개편"))
        val catalog = listOf(PortfolioCatalogEntry("recent_activities", "recent_activities", "최근 활동", "요약", emptyList()))

        assertEquals(topic, decisionAdapter.selectTopic("얼마나 빨라졌어?", emptyList(), listOf(topic)))
        assertEquals(
            PortfolioSelection(PortfolioSelectionIntent.GENERAL_CHAT, emptyList()),
            decisionAdapter.selectContext("안녕", emptyList(), catalog)
        )
        assertEquals(2, fallbackStreamingModel.callCount)
    }

    @Test
    fun `selectTopic falls back to GeminiAdapter on runtime HTTP failure when fallbackAdapter is present`() = runBlocking {
        val topic = ConversationTopic("ESM 로그인 개편", listOf("project_ESM_로그인_개편"))
        val fallbackStreamingModel = object : StreamingChatModel {
            var callCount = 0
            override fun stream(prompt: Prompt): Flux<ChatResponse> {
                callCount += 1
                return Flux.just(ChatResponse(listOf(Generation(AssistantMessage("""{"topicIndex":1}""")))))
            }
        }
        val meterRegistry = SimpleMeterRegistry()
        val fallbackAdapter = GeminiAdapter(
            streamingChatModel = fallbackStreamingModel,
            chatModelProperties = GeminiChatModelProperties().apply { models = listOf("gemini-3.8-flash") },
            promptTemplateService = FakePromptProvider,
            meterRegistry = meterRegistry
        )

        withServer(listOf(HttpStubResponse(503, "application/json", """{"error":{"message":"unavailable"}}"""))) { baseUrl, requests ->
            val decisionAdapter = adapter(
                baseUrl = baseUrl,
                meterRegistry = meterRegistry,
                fallbackAdapter = fallbackAdapter
            )

            val selected = decisionAdapter.selectTopic("얼마나 빨라졌어?", emptyList(), listOf(topic))

            assertEquals(topic, selected)
            assertEquals(1, requests.size)
            assertEquals(1, fallbackStreamingModel.callCount)
            assertEquals(
                1.0,
                meterRegistry.find("whoamai.chat.gemini.decision.fallback.total")
                    .tag("operation", "topic").counter()?.count()
            )
        }
    }

    @Test
    fun `rate limited 429 response triggers cooldown and skips subsequent decision HTTP calls`() = runBlocking {
        val catalog = listOf(
            PortfolioCatalogEntry("recent_activities", "recent_activities", "최근 활동", "요약", emptyList())
        )
        val topic = ConversationTopic("ESM 로그인 개편", listOf("project_ESM_로그인_개편"))
        val fallbackStreamingModel = object : StreamingChatModel {
            var callCount = 0
            override fun stream(prompt: Prompt): Flux<ChatResponse> {
                callCount += 1
                return Flux.just(ChatResponse(listOf(Generation(AssistantMessage("""{"topicIndex":1}""")))))
            }
        }
        val meterRegistry = SimpleMeterRegistry()
        val fallbackAdapter = GeminiAdapter(
            streamingChatModel = fallbackStreamingModel,
            chatModelProperties = GeminiChatModelProperties().apply { models = listOf("gemini-3.8-flash") },
            promptTemplateService = FakePromptProvider,
            meterRegistry = meterRegistry
        )

        withServer(listOf(HttpStubResponse(429, "application/json", """{"error":{"message":"quota exhausted"}}"""))) { baseUrl, requests ->
            val decisionAdapter = adapter(
                baseUrl = baseUrl,
                meterRegistry = meterRegistry,
                fallbackAdapter = fallbackAdapter
            )

            assertFailsWith<IllegalStateException> {
                decisionAdapter.selectContext("질문", emptyList(), catalog)
            }
            val cooldownError = assertFailsWith<IllegalStateException> {
                decisionAdapter.selectContext("두 번째 질문", emptyList(), catalog)
            }
            assertTrue(cooldownError.message.orEmpty().contains("cooling down"))

            val topicViaFallback = decisionAdapter.selectTopic("얼마나 빨라졌어?", emptyList(), listOf(topic))
            assertEquals(topic, topicViaFallback)
            assertEquals(1, requests.size)
            assertEquals(1, fallbackStreamingModel.callCount)
            assertEquals(
                1.0,
                meterRegistry.find("whoamai.chat.gemini.decision.cooldown_skipped.total")
                    .tag("operation", "catalog").counter()?.count()
            )
            assertEquals(
                1.0,
                meterRegistry.find("whoamai.chat.gemini.decision.cooldown_skipped.total")
                    .tag("operation", "topic").counter()?.count()
            )
        }
    }

    private fun adapter(
        baseUrl: String,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
        fallbackAdapter: GeminiAdapter? = null
    ): GeminiDecisionAdapter {
        return GeminiDecisionAdapter(
            decisionProperties = GeminiDecisionProperties(
                enabled = true,
                baseUrl = baseUrl,
                apiKey = "decision-test-key",
                model = "gemini-decision-1",
                timeoutMs = 2_000L
            ),
            meterRegistry = meterRegistry,
            fallbackAdapter = fallbackAdapter
        )
    }

    private suspend fun withServer(
        responses: List<HttpStubResponse>,
        action: suspend (String, List<RecordedRequest>) -> Unit
    ) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var cursor = 0
        server.createContext("/") { exchange ->
            requests += RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                apiKeyHeader = exchange.requestHeaders.getFirst("x-goog-api-key"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = exchange.requestBody.bufferedReader().use { it.readText() }
            )
            val stub = responses[cursor++]
            val bytes = stub.body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", stub.contentType)
            exchange.sendResponseHeaders(stub.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            action("http://127.0.0.1:${server.address.port}/v1beta", requests)
        } finally {
            server.stop(0)
        }
    }

    private data class HttpStubResponse(val status: Int, val contentType: String, val body: String)

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val apiKeyHeader: String?,
        val contentType: String?,
        val body: String
    )

    private object FakePromptProvider : PromptProvider {
        override fun systemInstruction(): String = ""
        override fun routingInstruction(): String = ""
        override fun renderRoutingTemplate(
            resumeOwnerName: String,
            companies: List<String>,
            skills: List<String>,
            question: String
        ): String = question

        override fun renderRagTemplate(context: String, question: String): String = question
        override fun renderConversationalTemplate(question: String): String = question
    }
}
