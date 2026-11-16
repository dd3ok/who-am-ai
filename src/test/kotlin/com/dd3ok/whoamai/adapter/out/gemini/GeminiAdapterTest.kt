package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.application.port.out.ConversationTopic
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeminiAdapterTest {
    private val loginTopic = ConversationTopic("ESM 로그인 개편", listOf("project_ESM_로그인_개편"))

    @Test
    fun `topic selection accepts only a supplied index or no inheritance`() = runTest {
        val model = RecordingStreamingChatModel(listOf(
            Flux.just(chatResponse("{\"topicIndex\":1}")), Flux.just(chatResponse("{\"topicIndex\":0}"))))
        val adapter = adapter(model)
        val topics = listOf(loginTopic)
        assertEquals(topics.single(), adapter.selectTopic("얼마나 빨라졌어?", emptyList(), topics))
        assertEquals(null, adapter.selectTopic("오늘 날씨는?", emptyList(), topics))
        assertEquals(null, adapter.selectTopic("얼마나 빨라졌어?", emptyList(), emptyList()))
        assertEquals(2, model.callCount)
    }

    @Test
    fun `malformed or invented topic responses cannot become a retrieval query`() = runTest {
        for (json in listOf("{}", "null", "{\"topicIndex\":-1}", "{\"topicIndex\":2}",
            "{\"topicIndex\":0.5}", "{\"topicIndex\":\"1\"}", "{\"topicIndex\":999999999999999999}",
            "{\"topicIndex\":1,\"topic\":\"invented\"}", "{\"topicIndex\":1} {}", "```json\n{\"topicIndex\":1}\n```")) {
            val model = RecordingStreamingChatModel(listOf(Flux.just(chatResponse(json))))
            assertFailsWith<Exception> {
                adapter(model).selectTopic("얼마나 빨라졌어?", emptyList(), listOf(loginTopic))
            }
            assertEquals(1, model.callCount)
        }
    }

    @Test
    fun `topic output followed by a provider error does not select a target`() = runTest {
        val model = RecordingStreamingChatModel(listOf(Flux.concat(
            Flux.just(chatResponse("{\"topicIndex\":1}")), Flux.error(IllegalStateException("provider failure")))))
        assertFailsWith<IllegalStateException> {
            adapter(model).selectTopic("얼마나 빨라졌어?", emptyList(), listOf(loginTopic))
        }
        assertEquals(1, model.callCount)
    }

    @Test
    fun `default max output tokens allows detailed chat responses`() {
        assertEquals(8192, GeminiChatModelProperties().maxOutputTokens)
    }

    @Test
    fun `streaming fallback is not attempted after partial output was emitted`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.concat(
                    Flux.just(chatResponse("partial")),
                    Flux.error(RuntimeException("429 quota exhausted"))
                ),
                Flux.just(chatResponse("fallback"))
            )
        )
        val adapter = adapter(streamingModel)
        val chunks = mutableListOf<String>()

        val error = try {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello")))
                .collect { chunks += it }
            null
        } catch (e: RuntimeException) {
            e
        }

        assertEquals("429 quota exhausted", error?.message)
        assertEquals(emptyList(), chunks)
        assertEquals(1, streamingModel.callCount)
    }

    @Test
    fun `streaming fallback is attempted when rate limit happens before any output`() = runTest {
        val meterRegistry = SimpleMeterRegistry()
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(RuntimeException("429 quota exhausted")),
                Flux.just(chatResponse("fallback"))
            )
        )
        val adapter = adapter(streamingModel, meterRegistry = meterRegistry)

        val chunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()

        assertEquals(listOf("fallback"), chunks)
        assertEquals(2, streamingModel.callCount)
        assertEquals(1.0, counterValue(meterRegistry, "whoamai.chat.gemini.model_attempt.total", "model", "primary"))
        assertEquals(1.0, counterValue(meterRegistry, "whoamai.chat.gemini.model_attempt.total", "model", "fallback"))
        assertEquals(1.0, counterValue(meterRegistry, "whoamai.chat.gemini.rate_limited.total", "model", "primary"))
    }

    @Test
    fun `streaming fallback detects nested rate limit causes`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(IllegalStateException("wrapped", RuntimeException("429 quota exhausted"))),
                Flux.just(chatResponse("fallback"))
            )
        )
        val adapter = adapter(streamingModel)

        val chunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()

        assertEquals(listOf("fallback"), chunks)
        assertEquals(2, streamingModel.callCount)
    }

    @Test
    fun `rate limited model is skipped while cooldown is active`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(RuntimeException("429 quota exhausted")),
                Flux.just(chatResponse("fallback-1")),
                Flux.just(chatResponse("fallback-2"))
            )
        )
        val adapter = adapter(streamingModel)

        val firstChunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        val secondChunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()

        assertEquals(listOf("fallback-1"), firstChunks)
        assertEquals(listOf("fallback-2"), secondChunks)
        assertEquals(listOf("primary", "fallback", "fallback"), streamingModel.requestedModels)
    }

    @Test
    fun `streaming fails fast while every model is cooling down`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(RuntimeException("429 primary")),
                Flux.error(RuntimeException("429 fallback")),
                Flux.just(chatResponse("unexpected"))
            )
        )
        val adapter = adapter(streamingModel)

        val firstError = assertFailsWith<RuntimeException> {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        }
        val secondError = assertFailsWith<IllegalStateException> {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        }

        assertEquals("429 fallback", firstError.message)
        assertTrue(secondError.message.orEmpty().startsWith("All Gemini chat models are cooling down"))
        assertEquals(2, streamingModel.callCount)
    }

    @Test
    fun `streaming propagates non rate limit errors without fallback`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(IllegalStateException("boom")),
                Flux.just(chatResponse("fallback"))
            )
        )
        val adapter = adapter(streamingModel)

        val error = assertFailsWith<IllegalStateException> {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        }

        assertEquals("boom", error.message)
        assertEquals(1, streamingModel.callCount)
    }

    @Test
    fun `streaming does not treat generate wording as rate limit`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(RuntimeException("failed to generate content")),
                Flux.just(chatResponse("fallback"))
            )
        )
        val adapter = adapter(streamingModel)

        val error = assertFailsWith<RuntimeException> {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        }

        assertEquals("failed to generate content", error.message)
        assertEquals(1, streamingModel.callCount)
    }

    @Test
    fun `streaming propagates final rate limit after priorities are exhausted`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(RuntimeException("429 first")),
                Flux.error(RuntimeException("429 final"))
            )
        )
        val adapter = adapter(streamingModel)

        val error = assertFailsWith<RuntimeException> {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        }

        assertEquals("429 final", error.message)
        assertEquals(2, streamingModel.callCount)
    }

    @Test
    fun `streaming ignores empty generation responses`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.just(
                    ChatResponse(emptyList()),
                    chatResponse("ok")
                )
            )
        )
        val adapter = adapter(streamingModel)

        val chunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()

        assertEquals(listOf("ok"), chunks)
        assertEquals(1, streamingModel.callCount)
    }

    @Test
    fun `streaming fallback is attempted when model completes without text chunks`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.just(ChatResponse(emptyList())),
                Flux.just(chatResponse("fallback"))
            )
        )
        val adapter = adapter(streamingModel)

        val chunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()

        assertEquals(listOf("fallback"), chunks)
        assertEquals(2, streamingModel.callCount)
    }

    @Test
    fun `streaming fails fast when no models are configured`() = runTest {
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatProperties = GeminiChatModelProperties()
        )

        val error = assertFailsWith<IllegalStateException> {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        }

        assertEquals("No models configured in Gemini properties.", error.message)
    }

    @Test
    fun `streaming ignores null blank and duplicate configured models`() = runTest {
        val streamingModel = RecordingStreamingChatModel(
            listOf(Flux.just(chatResponse("ok")))
        )
        val adapter = adapter(
            streamingModel = streamingModel,
            chatProperties = GeminiChatModelProperties().apply {
                models = listOf(null, " ", " fallback ", "fallback")
                model = "primary"
            }
        )

        val chunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()

        assertEquals(listOf("ok"), chunks)
        assertEquals(listOf("fallback"), streamingModel.requestedModels)
    }

    @Test
    fun `streaming fails fast when nullable model configuration is empty`() = runTest {
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatProperties = GeminiChatModelProperties().apply {
                models = null
                model = null
            }
        )

        val error = assertFailsWith<IllegalStateException> {
            adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()
        }

        assertEquals("No models configured in Gemini properties.", error.message)
    }

    private fun adapter(
        streamingModel: RecordingStreamingChatModel,
        chatProperties: GeminiChatModelProperties = GeminiChatModelProperties().apply {
            models = listOf("primary", "fallback")
        },
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry()
    ): GeminiAdapter {
        return GeminiAdapter(
            streamingChatModel = streamingModel,
            chatModelProperties = chatProperties,
            promptTemplateService = FakePromptProvider,
            meterRegistry = meterRegistry
        )
    }

    private class RecordingStreamingChatModel(
        private val responses: List<Flux<ChatResponse>>
    ) : org.springframework.ai.chat.model.StreamingChatModel {
        var callCount: Int = 0
        val requestedModels = mutableListOf<String>()

        override fun stream(prompt: Prompt): Flux<ChatResponse> {
            requestedModels += requireNotNull(prompt.options?.model)
            val response = responses.getOrElse(callCount) { Flux.error(IllegalStateException("unexpected call")) }
            callCount += 1
            return response
        }
    }

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

    companion object {
        private fun chatResponse(text: String): ChatResponse =
            ChatResponse(listOf(Generation(AssistantMessage(text))))

        private fun counterValue(
            meterRegistry: SimpleMeterRegistry,
            name: String,
            tagKey: String,
            tagValue: String
        ): Double {
            return meterRegistry.find(name).tag(tagKey, tagValue).counter()?.count() ?: 0.0
        }
    }
}
