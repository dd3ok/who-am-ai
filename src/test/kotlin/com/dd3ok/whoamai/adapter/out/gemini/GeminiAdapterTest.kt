package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.image.ImageModel
import org.springframework.ai.image.ImagePrompt
import org.springframework.ai.image.ImageResponse
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
    fun `non streaming ignores empty generation responses and tries next model`() = runTest {
        val chatModel = RecordingChatModel(
            listOf(
                ChatResponse(emptyList()),
                chatResponse("ok")
            )
        )
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatModel = chatModel
        )

        val response = adapter.generateContent("hello", "test")

        assertEquals("ok", response)
        assertEquals(2, chatModel.callCount)
    }

    @Test
    fun `non streaming fallback is attempted when rate limit happens before response`() = runTest {
        val chatModel = RecordingChatModel(
            listOf(
                RuntimeException("429 quota exhausted"),
                chatResponse("fallback")
            )
        )
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatModel = chatModel
        )

        val response = adapter.generateContent("hello", "test")

        assertEquals("fallback", response)
        assertEquals(2, chatModel.callCount)
    }

    @Test
    fun `non streaming propagates non rate limit errors without fallback`() = runTest {
        val chatModel = RecordingChatModel(
            listOf(
                IllegalStateException("boom"),
                chatResponse("fallback")
            )
        )
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatModel = chatModel
        )

        val error = assertFailsWith<IllegalStateException> {
            adapter.generateContent("hello", "test")
        }

        assertEquals("boom", error.message)
        assertEquals(1, chatModel.callCount)
    }

    @Test
    fun `non streaming throws final non rate limit error`() = runTest {
        val chatModel = RecordingChatModel(
            listOf(
                chatResponse(""),
                RuntimeException("provider unavailable")
            )
        )
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatModel = chatModel
        )

        val error = assertFailsWith<RuntimeException> {
            adapter.generateContent("hello", "test")
        }

        assertEquals("provider unavailable", error.message)
        assertEquals(2, chatModel.callCount)
    }

    @Test
    fun `non streaming throws when all models return empty responses`() = runTest {
        val chatModel = RecordingChatModel(
            listOf(
                chatResponse(""),
                ChatResponse(emptyList())
            )
        )
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatModel = chatModel
        )

        val error = assertFailsWith<IllegalStateException> {
            adapter.generateContent("hello", "test")
        }

        assertEquals("All models returned empty response.", error.message)
        assertEquals(2, chatModel.callCount)
    }

    @Test
    fun `non streaming propagates final rate limit after priorities are exhausted`() = runTest {
        val chatModel = RecordingChatModel(
            listOf(
                RuntimeException("429 first"),
                RuntimeException("429 final")
            )
        )
        val adapter = adapter(
            streamingModel = RecordingStreamingChatModel(emptyList()),
            chatModel = chatModel
        )

        val error = assertFailsWith<RuntimeException> {
            adapter.generateContent("hello", "test")
        }

        assertEquals("429 final", error.message)
        assertEquals(2, chatModel.callCount)
    }

    private fun adapter(
        streamingModel: RecordingStreamingChatModel,
        chatModel: ChatModel = NoopChatModel
    ): GeminiAdapter {
        val chatProperties = GeminiChatModelProperties().apply {
            models = listOf("primary", "fallback")
        }
        return GeminiAdapter(
            streamingChatModel = streamingModel,
            chatModel = chatModel,
            chatModelProperties = chatProperties,
            imageModelProperties = GeminiImageModelProperties(),
            promptTemplateService = FakePromptProvider,
            imageModel = NoopImageModel
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

    private class RecordingChatModel(
        private val responses: List<Any>
    ) : ChatModel {
        var callCount: Int = 0

        override fun call(prompt: Prompt): ChatResponse {
            val response = responses.getOrElse(callCount) { chatResponse("") }
            callCount += 1
            if (response is Throwable) {
                throw response
            }
            return response as ChatResponse
        }
    }

    private object NoopChatModel : ChatModel {
        override fun call(prompt: Prompt): ChatResponse = chatResponse("")
    }

    private object NoopImageModel : ImageModel {
        override fun call(request: ImagePrompt): ImageResponse = ImageResponse(emptyList())
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
