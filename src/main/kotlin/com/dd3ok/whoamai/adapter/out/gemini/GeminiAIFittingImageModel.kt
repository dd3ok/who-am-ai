package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import org.slf4j.LoggerFactory
import org.springframework.ai.image.Image
import org.springframework.ai.image.ImageGeneration
import org.springframework.ai.image.ImageModel
import org.springframework.ai.image.ImagePrompt
import org.springframework.ai.image.ImageResponse
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * 작업 목적: Google GenAI SDK를 활용해 Spring AI ImageModel 인터페이스를 구현한다.
 * 주요 로직: ImagePrompt + GeminiFittingImageOptions를 Google SDK 요청으로 변환하고,
 *            응답을 Spring AI ImageResponse로 매핑한다.
 */
@Component("geminiAIFittingImageModel")
class GeminiAIFittingImageModel(
    private val client: Client,
    private val imageModelProperties: GeminiImageModelProperties
) : ImageModel {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun call(request: ImagePrompt): ImageResponse {
        val options = request.options as? GeminiFittingImageOptions
            ?: throw IllegalArgumentException("GeminiFittingImageOptions is required for Gemini image generation.")

        val systemInstruction = Content.builder()
            .parts(listOf(Part.fromText(extractInstruction(request))))
            .build()

        val userContent = Content.builder()
            .role("user")
            .parts(listOf(
                Part.fromBytes(options.clothingImage, options.mimeType),
                Part.fromBytes(options.personImage, options.mimeType)
            ))
            .build()

        val config = GenerateContentConfig.builder()
            .systemInstruction(systemInstruction)
            .temperature(options.temperature ?: imageModelProperties.temperature)
            .apply {
                val seedValue = options.seed ?: imageModelProperties.seed
                seedValue?.let { seed(it) }
            }
            .build()

        val modelName = options.model ?: imageModelProperties.modelName

        val response = try {
            client.models.generateContent(modelName, listOf(userContent), config)
        } catch (e: Exception) {
            logger.error("Error while calling Gemini Image API: ${e.message}", e)
            throw e
        }

        val imageBytes = extractImageBytes(response)
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        return ImageResponse(listOf(ImageGeneration(Image(null, base64))))
    }

    private fun extractInstruction(request: ImagePrompt): String {
        return request.instructions
            .joinToString("\n") { it.text }
            .ifBlank { DEFAULT_INSTRUCTION }
    }

    private fun extractImageBytes(response: GenerateContentResponse): ByteArray {
        val candidates = response.candidates().orElse(emptyList())
        if (candidates.isEmpty()) {
            throw IllegalStateException("Gemini image API returned no candidates.")
        }

        val parts = candidates.first().content()
            .orElseThrow { IllegalStateException("Gemini image API returned empty content.") }
            .parts()
            .orElseThrow { IllegalStateException("Gemini image API returned empty parts.") }

        val inlineData = parts.firstNotNullOfOrNull { it.inlineData().orElse(null) }
            ?: throw IllegalStateException("Gemini image API returned no inline data.")

        return inlineData.data().orElse(null)
            ?: throw IllegalStateException("Gemini image API returned empty inline data.")
    }

    companion object {
        private const val DEFAULT_INSTRUCTION = "Please perform garment replacement according to the provided assets."
    }
}
