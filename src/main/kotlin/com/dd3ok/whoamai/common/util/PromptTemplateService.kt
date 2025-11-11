package com.dd3ok.whoamai.common.util

import com.dd3ok.whoamai.common.config.PromptProperties
import java.nio.charset.StandardCharsets
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

/**
 * 작업 목적: 프롬프트 텍스트를 외부 리소스에서 로드하고, 런타임에 템플릿을 렌더링한다.
 * 주요 로직: application.yml에는 경로만 유지하고, 해당 경로의 파일을 읽어 RAG/대화 프롬프트를 생성한다.
 */
@Component
class PromptTemplateService(
    private val resourceLoader: ResourceLoader,
    promptProperties: PromptProperties
) {

    val systemInstruction: String = loadTemplate(promptProperties.systemTemplate)

    private val ragTemplate: String = loadTemplate(promptProperties.ragTemplate)
    private val conversationalTemplate: String = loadTemplate(promptProperties.conversationalTemplate)

    fun buildRagPrompt(context: String, question: String): String {
        return ragTemplate
            .replace("{context}", context)
            .replace("{question}", question)
    }

    fun buildConversationalPrompt(question: String): String {
        return conversationalTemplate.replace("{question}", question)
    }

    private fun loadTemplate(location: String): String {
        val resource = resourceLoader.getResource(location)
        require(resource.exists()) { "Prompt resource not found: $location" }
        return resource.inputStream.use { input ->
            input.bufferedReader(StandardCharsets.UTF_8).readText()
        }
    }
}
