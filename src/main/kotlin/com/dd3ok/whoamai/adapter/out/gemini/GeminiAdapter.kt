package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.image.ImageModel
import org.springframework.ai.image.ImagePrompt
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * 작업 목적: Google GenAI 텍스트 경로는 Spring AI ChatModel/StreamingChatModel을 사용하고, 이미지 생성은 SDK Client를 사용한다.
 * 주요 로직: 도메인 `ChatMessage`를 Spring AI 메시지로 변환한 뒤 Prompt + 옵션을 구성해 호출하고, 이미지 전용 API는 Spring AI ImageModel 추상화를 통해 호출한다.
 */
@Component
class GeminiAdapter(
    private val streamingChatModel: StreamingChatModel,
    private val chatModel: ChatModel,
    private val chatModelProperties: GeminiChatModelProperties,
    private val imageModelProperties: GeminiImageModelProperties,
    private val promptTemplateService: PromptProvider,
    @Qualifier("geminiAIFittingImageModel")
    private val imageModel: ImageModel
) : GeminiPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class ChatPurposeConfig(
        val systemInstruction: String?,
        val temperature: Double,
        val maxOutputTokens: Int,
        val responseMimeType: String? = null
    )

    override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
        val messages = buildPromptMessages(history, promptTemplateService.systemInstruction())
        val prompt = Prompt(messages, defaultChatOptions())

        return streamingChatModel.stream(prompt)
            .asFlow()
            .map { it.result?.output?.text.orEmpty() }
            .filter { it.isNotBlank() }
    }

    override suspend fun generateContent(prompt: String, purpose: String): String = withContext(Dispatchers.IO) {
        val callConfig = resolvePurposeConfig(purpose)

        val messages = mutableListOf<Message>()
        callConfig.systemInstruction?.takeIf { it.isNotBlank() }?.let { messages += SystemMessage(it) }
        messages += UserMessage(prompt)

        val options = buildChatOptions(callConfig)

        try {
            val response = chatModel.call(Prompt(messages, options))
            response.result?.output?.text.orEmpty().trim()
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for purpose '$purpose': ${e.message}", e)
            ""
        }
    }

    override suspend fun generateStyledImage(
        personImageFile: ByteArray,
        clothingImageFile: ByteArray
    ): ByteArray = withContext(Dispatchers.IO) {
        val options = GeminiFittingImageOptions(
            personImageFile,
            clothingImageFile,
            imageModelProperties.mimeType,
            imageModelProperties.modelName,
            imageModelProperties.temperature,
            imageModelProperties.seed
        )
        val prompt = ImagePrompt(AI_FITTING_PROMPT, options)
        val response = imageModel.call(prompt)
        val generation = response.result
            ?: throw IllegalStateException("No image generation results returned from Gemini ImageModel.")
        val base64 = generation.output.b64Json
            ?: throw IllegalStateException("Gemini ImageModel returned empty image payload.")
        try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            logger.error("Failed to decode Gemini image payload: ${e.message}", e)
            throw e
        }
    }

    private fun defaultChatOptions(): GoogleGenAiChatOptions {
        return GoogleGenAiChatOptions.builder()
            .model(chatModelProperties.model)
            .temperature(chatModelProperties.temperature.toDouble())
            .maxOutputTokens(chatModelProperties.maxOutputTokens)
            .build()
    }

    private fun resolvePurposeConfig(purpose: String): ChatPurposeConfig {
        return when (purpose) {
            "routing" -> ChatPurposeConfig(
                systemInstruction = promptTemplateService.routingInstruction(),
                temperature = 0.1,
                maxOutputTokens = 512,
                responseMimeType = APPLICATION_JSON
            )
            "summarization" -> ChatPurposeConfig(
                systemInstruction = null,
                temperature = 0.5,
                maxOutputTokens = 1024
            )
            else -> ChatPurposeConfig(
                systemInstruction = promptTemplateService.systemInstruction(),
                temperature = chatModelProperties.temperature.toDouble(),
                maxOutputTokens = chatModelProperties.maxOutputTokens
            )
        }
    }

    private fun buildChatOptions(config: ChatPurposeConfig): GoogleGenAiChatOptions {
        val builder = GoogleGenAiChatOptions.builder()
            .model(chatModelProperties.model)
            .temperature(config.temperature)
            .maxOutputTokens(config.maxOutputTokens)

        config.responseMimeType?.let { builder.responseMimeType(it) }
        return builder.build()
    }

    private fun buildPromptMessages(history: List<ChatMessage>, systemInstruction: String?): List<Message> {
        val messages = mutableListOf<Message>()
        systemInstruction?.takeIf { it.isNotBlank() }?.let { messages += SystemMessage(it) }
        history.mapTo(messages, ::toAiMessage)
        return messages
    }

    private fun toAiMessage(chatMessage: ChatMessage): Message {
        return when (chatMessage.role.lowercase()) {
            "system" -> SystemMessage(chatMessage.text)
            "model", "assistant" -> AssistantMessage(chatMessage.text)
            else -> UserMessage(chatMessage.text)
        }
    }

    companion object {
        private const val APPLICATION_JSON = "application/json"

        private val AI_FITTING_PROMPT = """
<prompt>
  <meta>
    <directive>Task: Garment Replacement. All instructions are binding constraints.</directive>
    <directive>Redraw from scratch. Do not collage or paste.</directive>
  </meta>

  <thought_process>
    <step name="1. DECONSTRUCT">
      <instruction>Conceptually deconstruct the input images into core components:</instruction>
      <component id="STYLE">From input image 1: Isolate the visual style of the GARMENT(S) (color, texture, pattern, shape). Ignore the person wearing it and the background.</component>
      <component id="PERSON">From input image 2: Isolate the PERSON (face, body, pose, hair).</component>
      <component id="SCENE">From input image 2: Isolate the BACKGROUND and lighting conditions.</component>
    </step>

    <step name="2. REPLACE">
      <instruction>On the isolated <PERSON> component, identify and completely remove the original clothing. This creates a "blank canvas" on the person's body where the new garment will be placed.</instruction>
    </step>

    <step name="3. REASSEMBLE">
      <instruction>Reassemble the components into a new, final image:</instruction>
      <assembly_step>Start with the original <SCENE>.</assembly_step>
      <assembly_step>Place the <PERSON> (now without their original clothes) into the <SCENE>.</assembly_step>
      <assembly_step>Redraw the <STYLE> component onto the person's body, making it fit the pose and body shape naturally. The lighting on the new garment must match the <SCENE>.</assembly_step>
    </step>
  </thought_process>

  <absolute_rules>
    <rule priority="1">The <PERSON> and <SCENE> components from input image 2 are locked. They MUST NOT be altered. This is the highest priority.</rule>
    <rule>The replacement must be perfect. No traces of the original clothing should remain.</rule>
    <rule>If the <STYLE> component includes both a top and a bottom, replace both on the target.</rule>
  </absolute_rules>
</prompt>
        """.trimIndent()
    }
}
