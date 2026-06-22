package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.stereotype.Component

@Component
class GeminiAdapter(
    private val streamingChatModel: StreamingChatModel,
    private val chatModelProperties: GeminiChatModelProperties,
    private val promptTemplateService: PromptProvider
) : GeminiPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class ChatPurposeConfig(
        val temperature: Double,
        val maxOutputTokens: Int
    )

    override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
        val messages = buildPromptMessages(history, promptTemplateService.systemInstruction())
        val config = ChatPurposeConfig(
            temperature = chatModelProperties.temperature.toDouble(),
            maxOutputTokens = chatModelProperties.maxOutputTokens
        )
        return streamWithPriorities(messages, config)
    }

    private fun buildChatOptions(config: ChatPurposeConfig, model: String): GoogleGenAiChatOptions {
        return GoogleGenAiChatOptions.builder()
            .model(model)
            .temperature(config.temperature)
            .maxOutputTokens(config.maxOutputTokens)
            .build()
    }

    private fun buildPromptMessages(history: List<ChatMessage>, systemInstruction: String?): List<Message> {
        val messages = mutableListOf<Message>()
        systemInstruction?.takeIf { it.isNotBlank() }?.let { messages += SystemMessage(it) }
        history.mapTo(messages, ::toAiMessage)
        return messages
    }

    private fun streamWithPriorities(
        messages: List<Message>,
        config: ChatPurposeConfig
    ): Flow<String> = channelFlow {
        var lastError: Throwable? = null
        val models = modelPriority()

        for ((idx, model) in models.withIndex()) {
            val options = buildChatOptions(config, model)
            val prompt = Prompt(messages, options)
            var emittedAnyChunk = false

            try {
                streamingChatModel.stream(prompt)
                    .asFlow()
                    .mapNotNull { aiResponse ->
                        val text = aiResponse.results.firstOrNull()?.output?.text ?: return@mapNotNull null
                        text.takeIf { it.isNotBlank() }
                    }
                    .collect {
                        emittedAnyChunk = true
                        send(it)
                    }

                if (emittedAnyChunk) {
                    return@channelFlow
                }
                if (idx == models.lastIndex) {
                    throw IllegalStateException("All models returned empty stream.")
                }
                logger.warn("Model $model returned an empty stream. Trying next model.")
                delay(RETRY_BACKOFF_MS)
            } catch (e: Throwable) {
                lastError = e
                if (emittedAnyChunk || !isRateLimitException(e) || idx == models.lastIndex) {
                    throw e
                }
                logger.warn("Rate limit on model $model. Trying next model.")
                delay(RETRY_BACKOFF_MS)
            }
        }

        lastError?.let { throw it }
    }

    private fun isRateLimitException(e: Throwable): Boolean {
        val msg = e.message?.lowercase().orEmpty()
        return msg.contains("429") || msg.contains("quota") || msg.contains("rate") || msg.contains("exhausted")
    }

    private fun modelPriority(): List<String> {
        return chatModelProperties.models.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(chatModelProperties.model.takeIf { it.isNotBlank() })
    }

    private fun toAiMessage(chatMessage: ChatMessage): Message {
        return when (chatMessage.role.lowercase()) {
            "system" -> SystemMessage(chatMessage.text)
            "model", "assistant" -> AssistantMessage(chatMessage.text)
            else -> UserMessage(chatMessage.text)
        }
    }

    companion object {
        private const val RETRY_BACKOFF_MS = 300L
    }
}
