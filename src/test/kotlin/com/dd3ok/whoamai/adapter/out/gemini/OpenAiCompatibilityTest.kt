package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Real Spring AI HTTP serialization and SSE parsing, using a local server and a fake key only. */
@Timeout(20)
class OpenAiCompatibilityTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `configured provider uses new environment key and OpenAI streaming wire format`() {
        val stream = listOf(
            chunk("{\"role\":\"assistant\"}"),
            chunk("{\"content\":\"정상\"}"),
            chunk("{\"content\":\" 응답\"}"),
            chunk("{}", "\"stop\""),
            "data: [DONE]\n\n"
        ).joinToString("")

        withServer(200, "text/event-stream", stream) { baseUrl, requests ->
            contextRunner(baseUrl).run { context ->
                assertNull(context.startupFailure)
                assertEquals("gemini-embedding-local-test-only", context.environment.getProperty("spring.ai.google.genai.embedding.api-key"))
                val adapter = adapter(context.getBean(StreamingChatModel::class.java),
                    context.getBean(GeminiChatModelProperties::class.java))

                val answer = runBlocking {
                    adapter.generateChatContent(listOf(ChatMessage("user", "프로젝트를 설명해줘")))
                        .toList().joinToString("")
                }

                assertEquals("정상 응답", answer)
                val request = requests.single()
                assertEquals("POST", request.method)
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer hms-local-test-only", request.authorization)
                assertEquals("spring-ai-openai", request.userAgent)
                val body = mapper.readTree(request.body)
                assertEquals("deepseek-v4.1-flash-free", body["model"].stringValue())
                assertEquals(true, body["stream"].asBoolean())
                assertEquals(8192, body["max_tokens"].asInt())
                assertFalse(body.hasNonNull("max_completion_tokens"))
                assertEquals("system", body["messages"][0]["role"].stringValue())
                assertEquals("프로젝트를 설명해줘", body["messages"][1]["content"].stringValue())
                assertFalse(request.body.contains("hms-local-test-only"))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 429, 503])
    fun `provider failures are propagated without automatic HTTP retries`(status: Int) {
        withServer(status, "application/json", """{"error":{"message":"Provider request failed","type":"api_error","code":"test_error"}}""") { baseUrl, requests ->
            contextRunner(baseUrl).run { context ->
                assertNull(context.startupFailure)
                val adapter = adapter(context.getBean(StreamingChatModel::class.java),
                    context.getBean(GeminiChatModelProperties::class.java))

                assertFailsWith<Exception> {
                    runBlocking { adapter.generateChatContent(listOf(ChatMessage("user", "hello"))).toList() }
                }

                assertEquals(1, requests.size)
            }
        }
    }

    private fun contextRunner(baseUrl: String) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration::class.java))
        .withUserConfiguration(PropertiesConfig::class.java)
        .withBean(ToolCallingManager::class.java, Supplier { ToolCallingManager.builder().build() })
        .withInitializer { context ->
            YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                .forEach { context.environment.propertySources.addLast(it) }
        }
        .withPropertyValues(
            "_100MILION_API_KEY=hms-local-test-only",
            "GEMINI_API_KEY=gemini-embedding-local-test-only",
            "spring.ai.openai.base-url=$baseUrl",
            "spring.ai.openai.timeout=5s"
        )

    private fun adapter(model: StreamingChatModel, properties: GeminiChatModelProperties) =
        GeminiAdapter(model, properties, object : PromptProvider {
            override fun systemInstruction() = "Use the provided resume facts."
            override fun routingInstruction() = ""
            override fun renderRoutingTemplate(resumeOwnerName: String, companies: List<String>, skills: List<String>, question: String) = question
            override fun renderRagTemplate(context: String, question: String) = question
            override fun renderConversationalTemplate(question: String) = question
        }, SimpleMeterRegistry())

    private fun chunk(delta: String, finishReason: String = "null") =
        "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-v4.1-flash-free\",\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$finishReason}]}\n\n"

    private fun withServer(status: Int, contentType: String, response: String, action: (String, List<RecordedRequest>) -> Unit) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += RecordedRequest(exchange.requestMethod, exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("User-Agent"),
                exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            action("http://127.0.0.1:${server.address.port}/v1", requests)
        } finally {
            server.stop(0)
        }
    }

    private data class RecordedRequest(val method: String, val path: String, val authorization: String?, val userAgent: String?, val body: String)

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeminiChatModelProperties::class)
    private class PropertiesConfig
}
