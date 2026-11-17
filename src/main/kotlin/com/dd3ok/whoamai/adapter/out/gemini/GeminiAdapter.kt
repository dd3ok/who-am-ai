package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.application.port.out.ConversationTopicPort
import com.dd3ok.whoamai.application.port.out.ConversationTopic
import com.dd3ok.whoamai.application.port.out.PortfolioCatalogEntry
import com.dd3ok.whoamai.application.port.out.PortfolioCatalogSelectorPort
import com.dd3ok.whoamai.application.port.out.PortfolioSelection
import com.dd3ok.whoamai.application.port.out.PortfolioSelectionIntent
import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import io.micrometer.core.instrument.MeterRegistry
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
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@Component
class GeminiAdapter(
    private val streamingChatModel: StreamingChatModel,
    private val chatModelProperties: GeminiChatModelProperties,
    private val promptTemplateService: PromptProvider,
    private val meterRegistry: MeterRegistry
) : GeminiPort, ConversationTopicPort, PortfolioCatalogSelectorPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val rateLimitedUntilByModel = ConcurrentHashMap<String, Long>()
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    private val followUpInstruction by lazy {
        ClassPathResource("prompts/follow-up.st").inputStream.bufferedReader().use { it.readText() }
    }
    private val catalogSelectorInstruction by lazy {
        ClassPathResource("prompts/catalog-selector.st").inputStream.bufferedReader().use { it.readText() }
    }

    override suspend fun selectTopic(question: String, history: List<ChatMessage>, topics: List<ConversationTopic>): ConversationTopic? {
        if (topics.isEmpty()) return null
        val format = buildJsonSchemaFormat(
            properties = mapOf("topicIndex" to mapOf("type" to "integer", "enum" to (0..topics.size).toList())),
            required = listOf("topicIndex")
        )
        val input = mapOf(
            "question" to question,
            "history" to history,
            "topics" to topics.mapIndexed { index, topic -> mapOf("index" to index + 1, "title" to topic.title) }
        )
        val json = collectStructuredJson(followUpInstruction, input, format)
        val index = json.get("topicIndex")
        check(json.isObject && json.size() == 1 && index?.isIntegralNumber == true && index.canConvertToInt()) {
            "Invalid conversation topic response."
        }
        val selected = index.intValue()
        check(selected in 0..topics.size) { "Conversation topic response is outside the supplied candidates." }
        return if (selected == 0) null else topics[selected - 1]
    }

    override suspend fun selectContext(
        question: String,
        history: List<ChatMessage>,
        catalog: List<PortfolioCatalogEntry>
    ): PortfolioSelection {
        if (catalog.isEmpty()) return PortfolioSelection(PortfolioSelectionIntent.MISSING_EVIDENCE)
        val validIds = catalog.map { it.chunkId }
        val validIdSet = validIds.toSet()
        val format = buildJsonSchemaFormat(
            properties = mapOf(
                "intent" to mapOf("type" to "string", "enum" to PortfolioSelectionIntent.entries.map { it.name }),
                "chunkIds" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to validIds))
            ),
            required = listOf("intent", "chunkIds")
        )
        val input = mapOf(
            "question" to question,
            "history" to history.map { mapOf("role" to it.role, "text" to it.text) },
            "catalog" to catalog
        )
        val json = collectStructuredJson(catalogSelectorInstruction, input, format)
        check(json.isObject && json.size() == 2) { "Invalid catalog selection response." }
        val intentNode = json.get("intent")
        val chunkIdsNode = json.get("chunkIds")
        check(intentNode?.isString == true && chunkIdsNode?.isArray == true) {
            "Invalid catalog selection field types."
        }
        val intent = runCatching { PortfolioSelectionIntent.valueOf(intentNode.asString()) }
            .getOrElse { throw IllegalStateException("Invalid catalog selection intent: ${intentNode.asString()}") }
        val chunkIds = mutableListOf<String>()
        for (node in chunkIdsNode) {
            check(node.isString) { "Catalog selection chunkId must be a string." }
            val chunkId = node.asString()
            check(chunkId in validIdSet) { "Catalog selection chunkId is outside the supplied catalog: $chunkId" }
            chunkIds += chunkId
        }
        val distinctIds = chunkIds.distinct()
        if (intent == PortfolioSelectionIntent.PORTFOLIO_ANSWER) {
            check(distinctIds.isNotEmpty()) { "PORTFOLIO_ANSWER requires at least one catalog chunkId." }
        } else {
            check(distinctIds.isEmpty()) { "$intent must return an empty chunkIds array." }
        }
        return PortfolioSelection(intent = intent, chunkIds = distinctIds)
    }

    override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
        val messages = buildPromptMessages(history, promptTemplateService.systemInstruction())
        return streamWithPriorities(messages)
    }

    private fun buildJsonSchemaFormat(properties: Map<String, Any>, required: List<String>): ResponseFormat =
        ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(
                mapper.writeValueAsString(
                    mapOf(
                        "type" to "object",
                        "properties" to properties,
                        "required" to required,
                        "additionalProperties" to false
                    )
                )
            )
            .build()

    private suspend fun collectStructuredJson(
        instruction: String,
        payload: Any,
        format: ResponseFormat,
        reasoningEffort: String = chatModelProperties.selectorReasoningEffort
    ): JsonNode {
        val result = StringBuilder()
        streamWithPriorities(
            messages = listOf(SystemMessage(instruction), UserMessage(mapper.writeValueAsString(payload))),
            format = format,
            reasoningEffort = reasoningEffort
        ).collect { result.append(it) }
        return mapper.readTree(result.toString())
    }

    private fun buildChatOptions(
        model: String,
        format: ResponseFormat?,
        reasoningEffort: String = chatModelProperties.reasoningEffort
    ): OpenAiChatOptions {
        return OpenAiChatOptions.builder()
            .model(model)
            .reasoningEffort(reasoningEffort)
            .maxTokens(chatModelProperties.maxOutputTokens)
            .also { if (format != null) it.responseFormat(format) }
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
        format: ResponseFormat? = null,
        reasoningEffort: String = chatModelProperties.reasoningEffort
    ): Flow<String> = channelFlow {
        var lastError: Throwable? = null
        val models = availableModels().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("No models configured in Gemini properties.")

        for ((idx, model) in models.withIndex()) {
            recordModelAttempt(model)
            val options = buildChatOptions(model, format, reasoningEffort)
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
                    recordRateLimited(model)
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

    private fun recordModelAttempt(model: String) {
        meterRegistry.counter("whoamai.chat.gemini.model_attempt.total", "model", model).increment()
    }

    private fun recordRateLimited(model: String) {
        meterRegistry.counter("whoamai.chat.gemini.rate_limited.total", "model", model).increment()
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
