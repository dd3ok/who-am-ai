package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeminiAdapterTest {

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
        val streamingModel = RecordingStreamingChatModel(
            listOf(
                Flux.error(RuntimeException("429 quota exhausted")),
                Flux.just(chatResponse("fallback"))
            )
        )
        val adapter = adapter(streamingModel)

        val chunks = adapter.generateChatContent(listOf(ChatMessage(role = "user", text = "hello"))).toList()

        assertEquals(listOf("fallback"), chunks)
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

    private fun adapter(
        streamingModel: RecordingStreamingChatModel,
        chatProperties: GeminiChatModelProperties = GeminiChatModelProperties().apply {
            models = listOf("primary", "fallback")
        }
    ): GeminiAdapter {
        return GeminiAdapter(
            streamingChatModel = streamingModel,
            chatModelProperties = chatProperties,
            promptTemplateService = FakePromptProvider
        )
    }

    private class RecordingStreamingChatModel(
        private val responses: List<Flux<ChatResponse>>
    ) : org.springframework.ai.chat.model.StreamingChatModel {
        var callCount: Int = 0

        override fun stream(prompt: Prompt): Flux<ChatResponse> {
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
    }
}
