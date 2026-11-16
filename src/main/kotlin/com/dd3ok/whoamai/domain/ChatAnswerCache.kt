package com.dd3ok.whoamai.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CachedChatAnswer(
    val cacheKey: String,
    val normalizedPrompt: String,
    val answerText: String,
    val sources: List<ChatSource> = emptyList(),
    val resumeVersion: String = ""
)

object PromptCacheKey {
    private val MULTIPLE_WHITESPACE = Regex("\\s+")
    private val TRAILING_PUNCTUATION = Regex("[?!.。…~\\s]+$")

    fun normalize(prompt: String): String =
        prompt.trim()
            .replace(MULTIPLE_WHITESPACE, " ")
            .replace(TRAILING_PUNCTUATION, "")
            .lowercase()

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun build(normalizedPrompt: String, resumeVersion: String): String =
        sha256("$normalizedPrompt|$resumeVersion")
}
