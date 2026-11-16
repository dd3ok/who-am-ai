package com.dd3ok.whoamai.eval

import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withTimeout
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

/** Test-only evaluator using the application's existing Gemini connection, with its own system prompt. */
class GeminiAnswerJudge(
    private val model: StreamingChatModel,
    private val properties: GeminiChatModelProperties
) {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).enable(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES
    ).enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build()
    private val instruction = ClassPathResource("evals/answer-judge.st").inputStream.bufferedReader().use { it.readText() }
    private val responseFormat = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA).jsonSchema("""
        {
          "type": "object",
          "properties": {
            "verdict": {"type": "string", "enum": ["PASS", "FAIL", "REVIEW"]},
            "reason": {"type": "string"},
            "problemQuotes": {"type": "array", "items": {"type": "string"}},
            "evidence": {"type": "array", "items": {
              "type": "object",
              "properties": {"sourceId": {"type": "string"}, "quote": {"type": "string"}},
              "required": ["sourceId", "quote"],
              "additionalProperties": false
            }}
          },
          "required": ["verdict", "reason", "problemQuotes", "evidence"],
          "additionalProperties": false
        }
    """.trimIndent()).build()
    val modelName: String = requireNotNull(
        properties.models.orEmpty().firstOrNull { !it.isNullOrBlank() } ?: properties.model
    ).trim().also { require(it.isNotEmpty()) }

    suspend fun evaluate(sample: AnswerSample): AnswerJudgement = withTimeout(120_000) {
        require(sample.question.isNotBlank() && sample.answer.isNotBlank())
        require(sample.referenceSources.map { it.id }.distinct().size == sample.referenceSources.size)
        val prompt = Prompt(listOf(SystemMessage(instruction), UserMessage(mapper.writeValueAsString(sample))),
            OpenAiChatOptions.builder().model(modelName).reasoningEffort(properties.reasoningEffort)
                .maxTokens(properties.maxOutputTokens).responseFormat(responseFormat).build())
        val response = StringBuilder()
        model.stream(prompt).asFlow().collect { chunk ->
            chunk.results.firstOrNull()?.output?.text?.let(response::append)
        }
        // A timeout, provider error, malformed judgement or unverifiable citation must never become PASS.
        val judgement = mapper.readValue<AnswerJudgement>(response.toString())
        require(judgement.reason.isNotBlank()) { "Judge omitted its reason." }
        require(judgement.problemQuotes.all { it.isNotBlank() && sample.answer.contains(it) }) {
            "Judge quoted text that is absent from the candidate answer."
        }
        val references = sample.referenceSources.associateBy { it.id }
        require(judgement.evidence.all { citation ->
            citation.quote.isNotBlank() && references[citation.sourceId]?.text?.contains(citation.quote) == true
        }) { "Judge cited an unknown source or text absent from the reference." }
        if (judgement.verdict == Verdict.PASS) {
            require(judgement.evidence.isNotEmpty() && judgement.problemQuotes.isEmpty()) {
                "PASS requires verifiable evidence and no problematic answer quotes."
            }
        }
        judgement
    }
}

data class EvalSource(val id: String, val text: String)
data class AnswerSample(
    val question: String,
    val history: List<ChatMessage>,
    val referenceSources: List<EvalSource>,
    val retrievedContext: String,
    val answer: String
)
enum class Verdict { PASS, FAIL, REVIEW }
data class JudgeEvidence(val sourceId: String, val quote: String)
data class AnswerJudgement(
    val verdict: Verdict,
    val reason: String,
    val problemQuotes: List<String>,
    val evidence: List<JudgeEvidence>
)
