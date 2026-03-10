package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import com.dd3ok.whoamai.application.service.dto.QueryType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource

@SpringBootTest(
    properties = [
        "spring.data.mongodb.uri=\${MONGO_URI:mongodb://localhost:27017/whoam_ai_benchmark}",
        "spring.ai.google.genai.api-key=\${GEMINI_API_KEY:test-key}"
    ]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagRetrievalThresholdBenchmarkTest {

    @Autowired
    private lateinit var manageResumeUseCase: ManageResumeUseCase

    @Autowired
    private lateinit var llmRouter: LLMRouter

    @Autowired
    private lateinit var contextRetriever: ContextRetriever

    @Value("\${rag.search.similarity-threshold}")
    private var similarityThreshold: Double = 0.65

    @BeforeAll
    fun warmup() = runBlocking {
        assumeTrue(!System.getenv("GEMINI_API_KEY").isNullOrBlank(), "GEMINI_API_KEY is required")
        assumeTrue(!System.getenv("MONGO_URI").isNullOrBlank(), "MONGO_URI is required")
        val result = manageResumeUseCase.reindexResumeData()
        println("RETRIEVAL_BENCHMARK|phase=reindex|result=$result")
    }

    @Test
    fun runRetrievalBenchmark() = runBlocking {
        val allPrompts = ClassPathResource("evals/rag-benchmark-prompts.txt")
            .inputStream
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val maxPrompts = benchmarkMaxPrompts()
        val prompts = allPrompts.take(maxPrompts)

        var resumeRagCount = 0
        var totalContextCount = 0
        var zeroContextCount = 0
        val thresholdLabel = benchmarkThresholdLabel()

        prompts.forEachIndexed { index, prompt ->
            val start = System.currentTimeMillis()
            var contexts = contextRetriever.retrieveByRule(prompt)
            var path = if (contexts.isNotEmpty()) "rule" else "none"

            if (contexts.isEmpty()) {
                val routeDecision = llmRouter.route(prompt)
                if (routeDecision.queryType == QueryType.RESUME_RAG) {
                    resumeRagCount += 1
                    contexts = contextRetriever.retrieveByVector(prompt, routeDecision)
                    path = "vector"
                } else {
                    path = "non_rag"
                }
            } else {
                resumeRagCount += 1
            }

            if (contexts.isEmpty() && path != "non_rag") {
                zeroContextCount += 1
            }
            totalContextCount += contexts.size

            val elapsed = System.currentTimeMillis() - start
            println(
                "RETRIEVAL_BENCHMARK|threshold=$thresholdLabel" +
                    "|idx=$index|path=$path|latency_ms=$elapsed|context_count=${contexts.size}|q=${sanitize(prompt)}"
            )
        }

        val avgContext = if (prompts.isEmpty()) 0.0 else totalContextCount.toDouble() / prompts.size.toDouble()
        println(
            "RETRIEVAL_BENCHMARK_SUMMARY|threshold=$thresholdLabel" +
                "|prompt_count=${prompts.size}|resume_rag_count=$resumeRagCount|zero_context_count=$zeroContextCount|avg_context_count=$avgContext"
        )
    }

    private fun benchmarkThresholdLabel(): String {
        return System.getProperty("benchmark.threshold")
            ?: System.getenv("BENCHMARK_THRESHOLD")
            ?: similarityThreshold.toString()
    }

    private fun benchmarkMaxPrompts(): Int {
        return System.getProperty("benchmark.max-prompts")?.toIntOrNull()
            ?: System.getenv("BENCHMARK_MAX_PROMPTS")?.toIntOrNull()
            ?: 8
    }

    private fun sanitize(text: String): String = text.replace("|", "/").replace("\n", " ")
}
