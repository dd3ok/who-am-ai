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

    private fun adapter(streamingModel: RecordingStreamingChatModel): GeminiAdapter {
        val chatProperties = GeminiChatModelProperties().apply {
            models = listOf("primary", "fallback")
        }
        return GeminiAdapter(
            streamingChatModel = streamingModel,
            chatModel = NoopChatModel,
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
