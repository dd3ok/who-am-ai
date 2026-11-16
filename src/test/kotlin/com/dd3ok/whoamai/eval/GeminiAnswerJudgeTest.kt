package com.dd3ok.whoamai.eval

import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiAnswerJudgeTest {
    private val mapper = jacksonObjectMapper()
    private val sample = AnswerSample("어떤 기술을 썼어?", emptyList(),
        listOf(EvalSource("login", "Spring Boot와 Redis를 사용했습니다.")), "검색된 자료", "Spring Boot를 사용했습니다.")
    private val passing = AnswerJudgement(Verdict.PASS, "원문과 일치합니다.", emptyList(),
        listOf(JudgeEvidence("login", "Spring Boot")))

    @Test
    fun `judge uses a separate system prompt and sends only evaluation input`() = runTest {
        val model = StubModel(Flux.just(response(mapper.writeValueAsString(passing))))
        assertEquals(passing, judge(model).evaluate(sample))
        val prompt = model.prompt!!
        assertEquals(listOf("system", "user"), prompt.instructions.map { it.messageType.value })
        assertTrue(requireNotNull(prompt.instructions.first().text).contains("독립 평가자"))
        val input = mapper.readTree(prompt.instructions.last().text)
        assertFalse(input.has("expected"))
        assertEquals(sample.answer, input["answer"].stringValue())
        assertEquals(sample.retrievedContext, input["retrievedContext"].stringValue())
        assertEquals("gemini-3.8-flash", prompt.options?.model)
    }

    @Test
    fun `judge joins streamed JSON without changing the verdict`() = runTest {
        val json = mapper.writeValueAsString(passing)
        assertEquals(Verdict.PASS, judge(StubModel(Flux.fromIterable(json.chunked(11).map(::response)))).evaluate(sample).verdict)
    }

    @Test
    fun `review and fail remain non-passing outcomes`() = runTest {
        for (verdict in listOf(Verdict.REVIEW, Verdict.FAIL)) {
            val result = passing.copy(verdict = verdict, reason = "검토가 필요합니다.")
            assertEquals(verdict, judge(StubModel(Flux.just(response(mapper.writeValueAsString(result))))).evaluate(sample).verdict)
        }
    }

    @Test
    fun `malformed missing and contradictory verdicts cannot pass`() = runTest {
        val json = mapper.writeValueAsString(passing)
        for (invalid in listOf("", "```json\n$json\n```", "$json {}", "{}",
            json.replace("PASS", "MAYBE"), json.replace("\"PASS\"", "0"),
            mapper.writeValueAsString(passing.copy(reason = "")),
            mapper.writeValueAsString(passing.copy(evidence = emptyList())),
            mapper.writeValueAsString(passing.copy(problemQuotes = listOf("Spring Boot"))))) {
            assertFails { judge(StubModel(Flux.just(response(invalid)))).evaluate(sample) }
        }
    }

    @Test
    fun `invented citations and candidate quotes are rejected`() = runTest {
        for (invalid in listOf(
            passing.copy(evidence = listOf(JudgeEvidence("unknown", "Spring Boot"))),
            passing.copy(evidence = listOf(JudgeEvidence("login", "90% 개선"))),
            passing.copy(verdict = Verdict.FAIL, problemQuotes = listOf("후보에 없는 문장"))
        )) assertFails { judge(StubModel(Flux.just(response(mapper.writeValueAsString(invalid))))).evaluate(sample) }
    }

    @Test
    fun `partial output followed by provider error does not become a judgement`() = runTest {
        val stream = Flux.concat(Flux.just(response(mapper.writeValueAsString(passing))), Flux.error(RuntimeException("provider unavailable")))
        assertFailsWith<RuntimeException> { judge(StubModel(stream)).evaluate(sample) }
    }

    @Test
    fun `timeout is a failed evaluation rather than a pass`() = runTest {
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { judge(StubModel(Flux.never())).evaluate(sample) }
    }

    private fun judge(model: StreamingChatModel) = GeminiAnswerJudge(model,
        GeminiChatModelProperties(models = listOf("gemini-3.8-flash")))
    private fun response(text: String) = ChatResponse(listOf(Generation(AssistantMessage(text))))
    private class StubModel(private val response: Flux<ChatResponse>) : StreamingChatModel {
        var prompt: Prompt? = null
        override fun stream(prompt: Prompt): Flux<ChatResponse> { this.prompt = prompt; return response }
    }
}
