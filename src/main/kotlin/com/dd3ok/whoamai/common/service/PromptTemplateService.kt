package com.dd3ok.whoamai.common.service

import com.dd3ok.whoamai.common.config.PromptProperties
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

/**
 * 작업 목적: 프롬프트 리소스 파일을 로드해 서비스 전반에서 재사용 가능한 텍스트/템플릿을 제공한다.
 * 주요 로직: 설정에 지정된 리소스를 읽어 캐싱하고, 간단한 플레이스홀더 치환으로 RAG/라우팅/대화 프롬프트를 생성한다.
 */
interface PromptProvider {
    fun systemInstruction(): String
    fun routingInstruction(): String
    fun renderRoutingTemplate(
        resumeOwnerName: String,
        companies: List<String>,
        skills: List<String>,
        question: String
    ): String

    fun renderRagTemplate(context: String, question: String): String
    fun renderConversationalTemplate(question: String): String
}

@Service
class PromptTemplateService(
    private val resourceLoader: ResourceLoader,
    private val promptProperties: PromptProperties
) : PromptProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val templates: Map<TemplateType, String> by lazy { loadTemplates() }

    override fun systemInstruction(): String = templates.getValue(TemplateType.SYSTEM)

    override fun routingInstruction(): String = templates.getValue(TemplateType.ROUTING_INSTRUCTION)

    override fun renderRoutingTemplate(
        resumeOwnerName: String,
        companies: List<String>,
        skills: List<String>,
        question: String
    ): String = replacePlaceholders(
        templates.getValue(TemplateType.ROUTING_TEMPLATE),
        mapOf(
            "resume_owner_name" to resumeOwnerName,
            "companies_list" to companies.joinToString(", "),
            "skills_list" to skills.joinToString(", "),
            "question" to question
        )
    )

    override fun renderRagTemplate(context: String, question: String): String =
        replacePlaceholders(
            templates.getValue(TemplateType.RAG),
            mapOf("context" to context, "question" to question)
        )

    override fun renderConversationalTemplate(question: String): String =
        replacePlaceholders(
            templates.getValue(TemplateType.CONVERSATION),
            mapOf("question" to question)
        )

    private fun loadTemplates(): Map<TemplateType, String> {
        val paths = mapOf(
            TemplateType.SYSTEM to promptProperties.systemPath,
            TemplateType.ROUTING_INSTRUCTION to promptProperties.routingInstructionPath,
            TemplateType.ROUTING_TEMPLATE to promptProperties.routingTemplatePath,
            TemplateType.RAG to promptProperties.ragTemplatePath,
            TemplateType.CONVERSATION to promptProperties.conversationalTemplatePath
        )

        return paths.mapValues { (type, path) ->
            if (path.isBlank()) {
                throw IllegalStateException("Prompt path for $type is not configured.")
            }
            readResource(path)
        }
    }

    private fun readResource(path: String): String {
        val resource = resourceLoader.getResource(path)
        if (!resource.exists()) {
            throw IllegalStateException("Prompt resource not found at path=$path")
        }
        return resource.inputStream.use { inputStream ->
            inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    private fun replacePlaceholders(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return result
    }

    private enum class TemplateType {
        SYSTEM,
        ROUTING_INSTRUCTION,
        ROUTING_TEMPLATE,
        RAG,
        CONVERSATION
    }
}
