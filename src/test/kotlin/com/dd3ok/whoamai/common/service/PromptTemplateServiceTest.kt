package com.dd3ok.whoamai.common.service

import com.dd3ok.whoamai.common.config.PromptProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.DefaultResourceLoader
import java.nio.file.Files

class PromptTemplateServiceTest {

    @Test
    fun `rendering fails when template contains unknown identifier placeholder`() {
        val service = promptService(
            ragTemplate = "Context: {context}\nUnknown: {missing_value}\nQuestion: {question}"
        )

        val error = assertThrows<IllegalStateException> {
            service.renderRagTemplate("resume context", "question")
        }

        assertTrue(error.message.orEmpty().contains("missing_value"))
    }

    @Test
    fun `json braces in routing examples are not treated as placeholders`() {
        val service = promptService(
            routingTemplate = """
                Example: {"queryType":"NON_RAG"}
                Owner: {resume_owner_name}
                Companies: {companies_list}
                Skills: {skills_list}
                Question: {question}
            """.trimIndent()
        )

        val rendered = service.renderRoutingTemplate(
            resumeOwnerName = "유인재",
            companies = listOf("지마켓"),
            skills = listOf("Kotlin"),
            question = "지마켓 경력 알려줘"
        )

        assertTrue(rendered.contains("""{"queryType":"NON_RAG"}"""))
        assertEquals(false, rendered.contains("{question}"))
    }

    @Test
    fun `missing prompt template fails eagerly on startup construction`() {
        assertThrows<IllegalStateException> {
            PromptTemplateService(
                resourceLoader = DefaultResourceLoader(),
                promptProperties = PromptProperties(
                    systemPath = "classpath:prompts/does-not-exist.st"
                )
            )
        }
    }

    private fun promptService(
        system: String = "system",
        routingInstruction: String = "routing",
        routingTemplate: String = "Question: {question}",
        ragTemplate: String = "Context: {context}\nQuestion: {question}",
        conversationalTemplate: String = "Question: {question}"
    ): PromptTemplateService {
        val directory = Files.createTempDirectory("prompt-template-service-test")
        fun write(name: String, content: String): String {
            val path = directory.resolve(name)
            Files.writeString(path, content)
            return path.toUri().toString()
        }

        return PromptTemplateService(
            resourceLoader = DefaultResourceLoader(),
            promptProperties = PromptProperties(
                systemPath = write("system.st", system),
                routingInstructionPath = write("routing-instruction.st", routingInstruction),
                routingTemplatePath = write("routing-template.st", routingTemplate),
                ragTemplatePath = write("rag-template.st", ragTemplate),
                conversationalTemplatePath = write("conversational-template.st", conversationalTemplate)
            )
        )
    }
}
