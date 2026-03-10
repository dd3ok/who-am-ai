package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import com.dd3ok.whoamai.domain.MessageType
import com.dd3ok.whoamai.domain.StreamMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource

@SpringBootTest(
    properties = [
        "spring.data.mongodb.uri=\${MONGO_URI:mongodb://localhost:27017/whoam_ai_benchmark}",
        "spring.ai.google.genai.api-key=\${GEMINI_API_KEY:test-key}"
    ]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResumeQaAnswerTest {

    @Autowired
    private lateinit var chatUseCase: ChatUseCase

    @Autowired
    private lateinit var manageResumeUseCase: ManageResumeUseCase

    @BeforeAll
    fun warmup() = runBlocking {
        assumeTrue(!System.getenv("GEMINI_API_KEY").isNullOrBlank(), "GEMINI_API_KEY is required")
        assumeTrue(!System.getenv("MONGO_URI").isNullOrBlank(), "MONGO_URI is required")
        val result = manageResumeUseCase.reindexResumeData()
        println("RESUME_QA|phase=reindex|result=$result")
    }

    @Test
    fun printResumeAnswers() = runBlocking {
        val prompts = ClassPathResource("evals/resume-qa-prompts.txt")
            .inputStream
            .bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val maxPrompts = benchmarkMaxPrompts()
        val timeoutMs = benchmarkPromptTimeoutMs()

        prompts.take(maxPrompts).forEachIndexed { index, prompt ->
            try {
                val uuid = "resume-qa-${System.currentTimeMillis()}-$index"
                val answer = withTimeout(timeoutMs) {
                    chatUseCase.streamChatResponse(
                        StreamMessage(uuid = uuid, type = MessageType.USER, content = prompt)
                    ).toList().joinToString("")
                }

                println("RESUME_QA|idx=$index|q=${sanitize(prompt)}|a=${sanitize(answer)}")
            } catch (e: Exception) {
                println(
                    "RESUME_QA_ERROR|idx=$index|q=${sanitize(prompt)}|error=${sanitize(e.message ?: e::class.java.simpleName)}"
                )
            }
        }
    }

    private fun sanitize(text: String): String = text.replace("|", "/").replace("\n", " ").trim()

    private fun benchmarkMaxPrompts(): Int {
        return System.getProperty("benchmark.max-prompts")?.toIntOrNull()
            ?: System.getenv("BENCHMARK_MAX_PROMPTS")?.toIntOrNull()
            ?: 8
    }

    private fun benchmarkPromptTimeoutMs(): Long {
        return System.getProperty("benchmark.prompt-timeout-ms")?.toLongOrNull()
            ?: System.getenv("BENCHMARK_PROMPT_TIMEOUT_MS")?.toLongOrNull()
            ?: 25000L
    }
}
