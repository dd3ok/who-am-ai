package com.dd3ok.whoamai.common.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PromptBrandingTest {

    private val legacyNames = listOf(
        "\uC778\uC7AC AI",
        "\uC774\uB825\uC11C \uC5D0\uC774\uC804\uD2B8"
    )
    private val currentName = "\uCEE4\uB9AC\uC5B4 \uC5D0\uC774\uC804\uD2B8"

    @Test
    fun `prompt resources use career agent branding`() {
        val promptsPath = Path.of(
            requireNotNull(javaClass.classLoader.getResource("prompts")) {
                "prompts resource directory not found"
            }.toURI()
        )

        val promptTexts = Files.walk(promptsPath).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".st") }
                .map { it.fileName.toString() to Files.readString(it) }
                .toList()
        }

        assertTrue(promptTexts.isNotEmpty(), "Expected prompt resources to be present")

        promptTexts.forEach { (fileName, text) ->
            legacyNames.forEach { legacyName ->
                assertFalse(text.contains(legacyName), "Legacy branding remains in $fileName")
            }
        }

        listOf("system.st", "rag-template.st", "conversational-template.st").forEach { fileName ->
            val text = promptTexts.first { it.first == fileName }.second
            assertTrue(text.contains(currentName), "Current branding missing from $fileName")
        }
    }
}
