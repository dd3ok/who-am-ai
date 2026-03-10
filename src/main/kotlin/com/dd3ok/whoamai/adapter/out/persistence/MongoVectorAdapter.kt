package com.dd3ok.whoamai.adapter.out.persistence
	
import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.ai.document.Document as AiDocument
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore
import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreProperties
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

/**
 * 작업 목적: Spring AI MongoDB Atlas VectorStore를 사용해 이력서 Chunk를 색인·검색한다.
 * 주요 로직: Chunk 메타데이터를 VectorStore 문서로 변환해 적재하고, 필터 표현식 기반의 유사도 검색을 수행한다.
 */
@Component
class MongoVectorAdapter(
    private val vectorStore: MongoDBAtlasVectorStore,
    private val vectorStoreProperties: MongoDBAtlasVectorStoreProperties,
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
    @Value("\${rag.search.similarity-threshold:0.65}") private val similarityThreshold: Double
) : VectorDBPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun indexResume(chunks: List<ResumeChunk>): Int = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) {
            logger.warn("No resume chunks were provided. Skipping indexing.")
            return@withContext 0
        }

        reactiveMongoTemplate.remove(Query(), collectionName)
            .awaitSingleOrNull()

        val documents = chunks.map { chunk ->
            val metadata = buildMetadata(chunk).toMutableMap().apply {
                putIfAbsent("chunk_id", chunk.id)
            }

            AiDocument(
                chunk.content,
                metadata
            )
        }

        vectorStore.add(documents)
        logger.info("Successfully indexed {} resume chunks via Spring AI VectorStore.", documents.size)
        documents.size
    }

    override suspend fun searchSimilarResumeSections(
        query: String,
        topK: Int,
        filter: Filter.Expression?,
        similarityThreshold: Double?
    ): List<ResumeSearchResult> = withContext(Dispatchers.IO) {
        val builder = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(similarityThreshold ?: this@MongoVectorAdapter.similarityThreshold)
        filter?.let { builder.filterExpression(it) }

        vectorStore.similaritySearch(builder.build())
            .mapNotNull { document ->
                val content = document.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val chunkId = document.metadata["chunk_id"] as? String ?: return@mapNotNull null
                val chunkType = document.metadata["chunk_type"] as? String ?: inferChunkType(chunkId)
                ResumeSearchResult(
                    chunkId = chunkId,
                    chunkType = chunkType,
                    content = content
                )
            }
    }

    override suspend fun findChunkById(id: String): String? = withContext(Dispatchers.IO) {
        val query = Query(Criteria.where("metadata.chunk_id").`is`(id))
        reactiveMongoTemplate.findOne(query, ResumeChunkDocument::class.java, collectionName)
            .awaitSingleOrNull()
            ?.content
            ?: run {
                logger.warn("Resume chunk not found for chunk_id={}. Re-index might be required.", id)
                null
            }
    }

    override suspend fun findChunksByIds(ids: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()

        val query = Query(Criteria.where("metadata.chunk_id").`in`(ids))
        val documents = reactiveMongoTemplate.find(query, ResumeChunkDocument::class.java, collectionName)
            .collectList()
            .awaitSingleOrNull()
            .orEmpty()

        documents.mapNotNull { doc ->
            val chunkId = doc.metadata["chunk_id"] as? String ?: return@mapNotNull null
            chunkId to doc.content
        }.toMap()
    }

    private fun buildMetadata(chunk: ResumeChunk): Map<String, Any> {
        val metadata = mutableMapOf<String, Any>(
            "chunk_type" to chunk.type,
            "source" to chunk.source,
            "indexedAt" to chunk.indexedAt
        )
        chunk.company?.let { metadata["company"] = it }
        chunk.skills?.takeIf { it.isNotEmpty() }?.let { metadata["skills"] = it }
        return metadata
    }

    companion object {
        const val COLLECTION_NAME = "resume_chunks"
        const val VECTOR_INDEX_NAME = "vector_index"
    }

    private val collectionName: String
        get() = vectorStoreProperties.collectionName.ifBlank { COLLECTION_NAME }

    private fun inferChunkType(chunkId: String): String = when {
        chunkId.startsWith("project_") -> "project"
        chunkId.startsWith("experience_") && chunkId != "experience_total_summary" -> "experience"
        chunkId == "experience_total_summary" -> "summary"
        else -> chunkId
    }
}
