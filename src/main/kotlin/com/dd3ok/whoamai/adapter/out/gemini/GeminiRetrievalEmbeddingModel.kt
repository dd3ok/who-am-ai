package com.dd3ok.whoamai.adapter.out.gemini

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse

/** Embedding 2 retrieval instructions belong in API inputs, not stored portfolio text. */
class GeminiRetrievalEmbeddingModel(
    private val delegate: EmbeddingModel,
    private val dimensions: Int
) : EmbeddingModel {
    override fun embed(text: String): FloatArray =
        delegate.embed("task: search result | query: $text")

    override fun embed(document: Document): FloatArray = delegate.embed(getEmbeddingContent(document))

    // Spring AI's batch document path calls this method rather than embed(Document).
    override fun getEmbeddingContent(document: Document): String {
        val source = document.metadata["source"] as? Map<*, *>
        val title = (source?.get("title") as? String)?.takeIf { it.isNotBlank() } ?: "none"
        return "title: $title | text: ${document.text.orEmpty()}"
    }

    override fun call(request: EmbeddingRequest): EmbeddingResponse = delegate.call(request)

    override fun dimensions(): Int = dimensions
}
