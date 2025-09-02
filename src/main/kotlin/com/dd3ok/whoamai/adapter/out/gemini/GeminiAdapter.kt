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