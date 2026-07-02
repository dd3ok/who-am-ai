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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@Component
class GeminiAdapter(
    private val streamingChatModel: StreamingChatModel,
    private val chatModelProperties: GeminiChatModelProperties,
    private val promptTemplateService: PromptProvider
) : GeminiPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val rateLimitedUntilByModel = ConcurrentHashMap<String, Long>()

    override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
        val messages = buildPromptMessages(history, promptTemplateService.systemInstruction())
        return streamWithPriorities(messages)
    }

    private fun buildChatOptions(model: String): GoogleGenAiChatOptions {
        return GoogleGenAiChatOptions.builder()
            .model(model)
            .temperature(chatModelProperties.temperature.toDouble())
            .maxOutputTokens(chatModelProperties.maxOutputTokens)
            .build()
    }

    private fun buildPromptMessages(history: List<ChatMessage>, systemInstruction: String?): List<Message> {
        val messages = mutableListOf<Message>()
        systemInstruction?.takeIf { it.isNotBlank() }?.let { messages += SystemMessage(it) }
        history.mapTo(messages, ::toAiMessage)
        return messages
    }

    private fun streamWithPriorities(messages: List<Message>): Flow<String> = channelFlow {
        var lastError: Throwable? = null
        val models = availableModels().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("No models configured in Gemini properties.")

        for ((idx, model) in models.withIndex()) {
            val options = buildChatOptions(model)
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
                val rateLimited = isRateLimitException(e)
                if (rateLimited) {
                    markRateLimited(model)
                }
                if (emittedAnyChunk || !rateLimited || idx == models.lastIndex) {
                    throw e
                }
                logger.warn("Rate limit on model $model. Trying next model.")
                delay(RETRY_BACKOFF_MS)
            }
        }

        lastError?.let { throw it }
    }

    private fun isRateLimitException(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val msg = current.message?.lowercase().orEmpty()
            val rateLimited = msg.contains("429") ||
                msg.contains("quota") ||
                msg.contains("rate limit") ||
                msg.contains("rate-limit") ||
                msg.contains("too many requests") ||
                msg.contains("resource_exhausted") ||
                msg.contains("exhausted")

            if (rateLimited) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun availableModels(): List<String> {
        val models = modelPriority()
        if (models.isEmpty()) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val available = models.filterNot { isCoolingDown(it, now) }
        if (available.isNotEmpty()) {
            return available
        }

        val retryAfterMs = models.mapNotNull(rateLimitedUntilByModel::get).minOrNull()
            ?.let { max(MIN_RETRY_AFTER_MS, it - now) }
            ?: chatModelProperties.rateLimitCooldownMs
        throw IllegalStateException("All Gemini chat models are cooling down after rate limit responses. Retry in about ${retryAfterMs}ms.")
    }

    private fun isCoolingDown(model: String, now: Long): Boolean {
        val until = rateLimitedUntilByModel[model] ?: return false
        if (until <= now) {
            rateLimitedUntilByModel.remove(model, until)
            return false
        }
        return true
    }

    private fun markRateLimited(model: String) {
        val cooldownMs = chatModelProperties.rateLimitCooldownMs
        if (cooldownMs <= 0L) {
            return
        }
        rateLimitedUntilByModel[model] = System.currentTimeMillis() + cooldownMs
    }

    private fun modelPriority(): List<String> {
        val configuredModels = chatModelProperties.models
            .orEmpty()
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinct()

        return configuredModels.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(chatModelProperties.model?.trim()?.takeIf { it.isNotBlank() })
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
        private const val MIN_RETRY_AFTER_MS = 1L
    }
}
