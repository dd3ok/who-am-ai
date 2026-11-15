package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.StreamMessage
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource

@SpringBootTest(
    properties = [
        "spring.mongodb.uri=\${EVAL_MONGO_URI:mongodb://localhost:27017/whoam_ai_benchmark}",
        "spring.ai.google.genai.api-key=\${GEMINI_API_KEY:test-key}",
        "spring.ai.google.genai.embedding.api-key=\${GEMINI_API_KEY:test-key}"
    ]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("live")
class RagThresholdBenchmarkTest {

    @Autowired
    private lateinit var chatUseCase: ChatUseCase

    @Autowired
    private lateinit var manageResumeUseCase: ManageResumeUseCase

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Value("\${rag.search.similarity-threshold-override:-1}")
    private var similarityThreshold: Double = -1.0

    @BeforeAll
    fun warmup() = runBlocking {
        assumeTrue(!System.getenv("GEMINI_API_KEY").isNullOrBlank(), "GEMINI_API_KEY is required")
        assumeTrue(!System.getenv("EVAL_MONGO_URI").isNullOrBlank(), "EVAL_MONGO_URI is required")
        val result = manageResumeUseCase.reindexResumeData()
        println("BENCHMARK|phase=reindex|result=$result")
    }

    @Test
    fun runRagBenchmark() = runBlocking {
        val allPrompts = ClassPathResource("evals/rag-benchmark-prompts.txt")
            .inputStream
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val maxPrompts = benchmarkMaxPrompts()
        val timeoutMs = benchmarkPromptTimeoutMs()
        val prompts = allPrompts.take(maxPrompts)
        val thresholdLabel = similarityThreshold.takeIf { it in 0.0..1.0 }?.toString() ?: "adaptive"

        val beforeEmpty = counterValue("whoamai.rag.empty_context.total")
        var successCount = 0
        var failedCount = 0

        prompts.forEachIndexed { index, prompt ->
            try {
                val uuid = "benchmark-${System.currentTimeMillis()}-$index"
                val start = System.currentTimeMillis()
                val chunks = withTimeout(timeoutMs) {
                    chatUseCase.streamChatResponse(
                        StreamMessage(uuid = uuid, type = MessageType.USER, content = prompt)
                    ).toList()
                }
                val elapsedMs = System.currentTimeMillis() - start
                val answer = chunks.joinToString("")
                assertTrue(answer.isNotBlank(), "Empty answer for prompt $index")
                successCount += 1

                println(
                    "BENCHMARK|threshold=$thresholdLabel" +
                        "|idx=$index|latency_ms=$elapsedMs|chars=${answer.length}|q=${sanitize(prompt)}"
                )
            } catch (e: Exception) {
                failedCount += 1
                println(
                    "BENCHMARK_ERROR|threshold=$thresholdLabel" +
                        "|idx=$index|error=${sanitize(e.message ?: e::class.java.simpleName)}|q=${sanitize(prompt)}"
                )
            }
        }

        val afterEmpty = counterValue("whoamai.rag.empty_context.total")
        val deltaEmpty = afterEmpty - beforeEmpty

        println(
            "BENCHMARK_SUMMARY|threshold=$thresholdLabel" +
                "|prompt_count=${prompts.size}|success_count=$successCount|failed_count=$failedCount|empty_context_delta=$deltaEmpty"
        )
        assertEquals(0, failedCount, "Live benchmark requests failed; inspect BENCHMARK_ERROR lines.")
    }

    private fun counterValue(name: String): Double {
        return meterRegistry.find(name).counter()?.count() ?: 0.0
    }

    private fun benchmarkMaxPrompts(): Int {
        return System.getProperty("benchmark.max-prompts")?.toIntOrNull()
            ?: System.getenv("BENCHMARK_MAX_PROMPTS")?.toIntOrNull()
            ?: 5
    }

    private fun benchmarkPromptTimeoutMs(): Long {
        return System.getProperty("benchmark.prompt-timeout-ms")?.toLongOrNull()
            ?: System.getenv("BENCHMARK_PROMPT_TIMEOUT_MS")?.toLongOrNull()
            ?: 25000L
    }

    private fun sanitize(text: String): String {
        return text.replace("|", "/").replace("\n", " ")
    }
}
