package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.application.port.out.EmbeddingPort
import com.google.genai.Client
import com.google.genai.types.EmbedContentConfig
import com.google.genai.types.EmbedContentResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class GeminiApiEmbeddingAdapter(
    @Value("\${gemini.api.key}") private val apiKey: String,
    @Value("\${gemini.model.text}") private val modelName: String
) : EmbeddingPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val client: Client by lazy {
        logger.info("Initializing Google Gen AI Client for Embedding...")
        Client.builder()
            .apiKey(apiKey)
            .build()
    }

    override suspend fun embedContent(text: String): List<Float> = withContext(Dispatchers.IO) {
        try {
            val config = EmbedContentConfig.builder().build()
            val response: EmbedContentResponse = client.models.embedContent(modelName, text, config)

            val embeddingList = response.embeddings().orElse(emptyList())

            if (embeddingList.isNotEmpty()) {
                val firstEmbedding = embeddingList.first()
                return@withContext firstEmbedding.values().orElse(emptyList())
            } else {
                logger.error("Embedding list was empty for the response.")
                return@withContext emptyList()
            }

        } catch (e: Exception) {
            logger.error("Error while calling Gemini API for embedding: ${e.message}", e)
            emptyList<Float>()
        }
    }
}