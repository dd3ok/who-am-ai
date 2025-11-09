package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import org.slf4j.LoggerFactory
import org.springframework.ai.image.Image
import org.springframework.ai.image.ImageGeneration
import org.springframework.ai.image.ImageModel
import org.springframework.ai.image.ImagePrompt
import org.springframework.ai.image.ImageResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.util.Base64

/**
 * 작업 목적: Google GenAI HTTP API를 호출해 Spring AI ImageModel 인터페이스를 구현한다.
 * 주요 로직: ImagePrompt + GeminiFittingImageOptions를 REST 요청 페이로드로 변환하고
 *            응답의 inlineData를 Base64 이미지로 추출한다.
 */
@Component("geminiAIFittingImageModel")
class GeminiAIFittingImageModel(
    private val imageModelProperties: GeminiImageModelProperties,
    @Value("\${spring.ai.google.genai.api-key}") private val genAiApiKey: String
) : ImageModel {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(imageModelProperties.baseUrl)
        .build()

    override fun call(request: ImagePrompt): ImageResponse {
        val options = request.options as? GeminiFittingImageOptions
            ?: throw IllegalArgumentException("GeminiFittingImageOptions is required for Gemini image generation.")

        val payload = buildRequestPayload(request, options)
        val modelName = options.model ?: imageModelProperties.modelName

        val response = try {
            restClient.post()
                .uri { uriBuilder ->
                    uriBuilder.path(imageModelProperties.generatePath)
                        .queryParam("key", genAiApiKey)
                        .build(modelName)
                }
                .body(payload)
                .retrieve()
                .body(GenerateContentResponse::class.java)
                ?: throw IllegalStateException("Gemini image API returned empty response.")
        } catch (ex: RestClientException) {
            logger.error("Error while calling Gemini image endpoint: ${ex.message}", ex)
            throw ex
        }

        val imageBytes = extractImageBytes(response)
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        return ImageResponse(listOf(ImageGeneration(Image(null, base64))))
    }

    private fun buildRequestPayload(
        request: ImagePrompt,
        options: GeminiFittingImageOptions
    ): GenerateContentRequest {
        val instruction = extractInstruction(request)
        val clothing = encodeInlineData(options.clothingImage, options.mimeType)
        val person = encodeInlineData(options.personImage, options.mimeType)
        val promptParts = mutableListOf(
            Part(text = instruction),
            Part(inlineData = clothing),
            Part(inlineData = person)
        )

        val generationConfig = GenerationConfig(
            temperature = (options.temperature ?: imageModelProperties.temperature).toDouble(),
            seed = options.seed ?: imageModelProperties.seed
        )

        val systemInstruction = Content(
            role = "system",
            parts = listOf(Part(text = instruction))
        )

        val userContent = Content(
            role = "user",
            parts = promptParts
        )

        return GenerateContentRequest(
            contents = listOf(userContent),
            systemInstruction = systemInstruction,
            generationConfig = generationConfig
        )
    }

    private fun extractInstruction(request: ImagePrompt): String {
        return request.instructions
            .joinToString("\n") { it.text }
            .ifBlank { DEFAULT_INSTRUCTION }
    }

    private fun encodeInlineData(source: ByteArray, mimeType: String): InlineData {
        val base64 = Base64.getEncoder().encodeToString(source)
        return InlineData(mimeType = mimeType, data = base64)
    }

    private fun extractImageBytes(response: GenerateContentResponse): ByteArray {
        val encoded = response.candidates.orEmpty()
            .asSequence()
            .mapNotNull { it.content }
            .flatMap { it.parts.orEmpty().asSequence() }
            .mapNotNull { it.inlineData?.data }
            .firstOrNull()
            ?: throw IllegalStateException("Gemini image API returned no inline data.")

        return try {
            Base64.getDecoder().decode(encoded)
        } catch (ex: IllegalArgumentException) {
            logger.error("Failed to decode Gemini image payload: ${ex.message}", ex)
            throw ex
        }
    }

    private data class GenerateContentRequest(
        val contents: List<Content>,
        val systemInstruction: Content,
        val generationConfig: GenerationConfig
    )

    private data class Content(
        val role: String? = null,
        val parts: List<Part>
    )

    private data class Part(
        val text: String? = null,
        val inlineData: InlineData? = null
    )

    private data class InlineData(
        val mimeType: String,
        val data: String
    )

    private data class GenerationConfig(
        val temperature: Double,
        val seed: Int?
    )

    private data class GenerateContentResponse(
        val candidates: List<Candidate>?
    )

    private data class Candidate(
        val content: CandidateContent?
    )

    private data class CandidateContent(
        val parts: List<Part>?
    )

    companion object {
        private const val DEFAULT_INSTRUCTION = "Please perform garment replacement according to the provided assets."
    }
}
