package com.dd3ok.whoamai.common.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PromptBrandingTest {

    private val forcedSelfBrandingPhrases = listOf(
        "\uC778\uC7AC AI",
        "\uC774\uB825\uC11C \uC5D0\uC774\uC804\uD2B8",
        "\uCEE4\uB9AC\uC5B4 \uC5D0\uC774\uC804\uD2B8"
    )

    @Test
    fun `prompt resources do not force agent self branding`() {
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
            forcedSelfBrandingPhrases.forEach { phrase ->
                assertFalse(text.contains(phrase), "Forced self-branding phrase remains in $fileName: $phrase")
            }
        }
    }
}
