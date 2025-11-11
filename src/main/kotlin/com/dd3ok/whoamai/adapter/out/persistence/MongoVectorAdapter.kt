package com.dd3ok.whoamai.adapter.out.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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
    private val reactiveMongoTemplate: ReactiveMongoTemplate
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
        filter: Filter.Expression?
    ): List<String> = withContext(Dispatchers.IO) {
        val builder = SearchRequest.builder()
            .query(query)
            .topK(topK)
        filter?.let { builder.filterExpression(it) }

        vectorStore.similaritySearch(builder.build())
            .mapNotNull { it.text }
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

    override suspend fun findChunksByIds(ids: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) {
            return@withContext emptyMap()
        }

        val query = Query(Criteria.where("metadata.chunk_id").`in`(ids))
        reactiveMongoTemplate.find(query, ResumeChunkDocument::class.java, collectionName)
            .collectList()
            .awaitSingle()
            .associate { document ->
                val chunkId = (document.metadata["chunk_id"] as? String).orEmpty().ifBlank { document.id }
                chunkId to document.content
            }
    }

    private fun buildMetadata(chunk: ResumeChunk): Map<String, Any> {
        val metadata = mutableMapOf<String, Any>(
            "chunk_type" to chunk.type,
            "source" to chunk.source
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
}
