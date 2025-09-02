package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.GeminiPort
import com.dd3ok.whoamai.common.config.GeminiChatModelProperties
import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import com.dd3ok.whoamai.domain.ChatMessage
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class GeminiAdapter(
    private val client: Client,
    private val chatModelProperties: GeminiChatModelProperties,
    private val imageModelProperties: GeminiImageModelProperties,
    @Qualifier("generationConfig") private val generationConfig: GenerateContentConfig,
    @Qualifier("routingConfig") private val routingConfig: GenerateContentConfig,
    @Qualifier("summarizationConfig") private val summarizationConfig: GenerateContentConfig
) : GeminiPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun generateChatContent(history: List<ChatMessage>): Flow<String> {
        val apiHistory = history.map { msg ->
            Content.builder()
                .role(msg.role)
                .parts(Part.fromText(msg.text))
                .build()
        }

        return try {
            val responseStream = client.models
                .generateContentStream(chatModelProperties.name, apiHistory, generationConfig)

            flow {
                for (response in responseStream) {
                    response.text()?.let { emit(it) }
                }
            }
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API: ${e.message}", e)
            flowOf("죄송합니다, AI 응답 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        }
    }

    override suspend fun generateContent(prompt: String, purpose: String): String {
        val config = when (purpose) {
            "routing" -> routingConfig
            "summarization" -> summarizationConfig
            else -> generationConfig
        }

        return try {
            val response = client.models.generateContent(chatModelProperties.name, prompt, config)
            response.text() ?: ""
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for purpose '$purpose': ${e.message}", e)
            ""
        }
    }

    suspend fun generateImageContent(clothingImagePart: Part, personImagePart: Part): GenerateContentResponse {
        val instructionParts = listOf(
            Part.fromText("""
<prompt>
  <meta>
    <directive>Interpret all instructions as binding constraints.</directive>
    <directive>Redraw the entire image from scratch. Do not collage or paste from references.</directive>
  </meta>

  <input_images>
    <source id="1">Image containing the GARMENT to use. Ignore everything else (person, background, etc.).</source>
    <target id="2">Image of the PERSON and SCENE to apply the garment to. This is the base to be preserved.</target>
  </input_images>

  <core_objective>
    Replace the garment worn by the person in the <target> image with the garment from the <source> image.
    The new garment must be redrawn to naturally fit the target person's body shape and pose.
  </core_objective>

  <key_rules>
    <rule priority="1">
      The person's face, hair, body, and pose in the <target> image MUST remain unchanged. The background must also be perfectly preserved. This is the highest priority.
    </rule>
    <rule priority="2">
      The original garment from the <target> image must be completely removed. No traces should remain.
    </rule>
    <rule priority="3">
      The new garment must integrate seamlessly. Match the lighting, shadows, folds, and wrinkles to the <target> scene.
    </rule>
    <rule priority="4">
      Forbidden actions: Do not use collage or cut-and-paste methods. Do not change the person's identity. Do not add watermarks or text.
    </rule>
  </key_rules>

  <output>
    A single, clean, high-quality image.
  </output>
</prompt>

    """.trimIndent())
        )

        val systemInstruction = Content.builder().parts(instructionParts).build()
        val config = GenerateContentConfig.builder()
            .systemInstruction(systemInstruction)
            .seed(1234)
            .temperature(0.3f)
//            .topP(0.8f)
//            .topK(30.0f)
            .build()

        val userContent = Content.builder()
            .role("user")
            .parts(listOf(clothingImagePart, personImagePart))
            .build()

        val contents = listOf(userContent)

        return try {
            client.models.generateContent(imageModelProperties.name, contents, config)
        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for AIFitting: ${e.message}", e)
            throw e
        }
    }
}