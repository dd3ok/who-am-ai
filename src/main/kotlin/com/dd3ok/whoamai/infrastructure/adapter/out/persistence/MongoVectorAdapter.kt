package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.EmbeddingPort
import com.dd3ok.whoamai.application.port.out.VectorDBPort
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.TypedAggregation
import org.springframework.data.mongodb.core.findById
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class MongoVectorAdapter(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val embeddingPort: EmbeddingPort
) : VectorDBPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val COLLECTION_NAME = "resume_chunks"
        const val VECTOR_INDEX_NAME = "vector_index"
        private const val NUM_CANDIDATES_MULTIPLIER = 10
    }

    override suspend fun indexResume(chunks: List<ResumeChunk>): Int {
        return mongoTemplate.dropCollection(ResumeChunkDocument::class.java)
            .thenMany(
                Flux.fromIterable(chunks)
                    .flatMap { chunk ->
                        mono { embeddingPort.embedContent(chunk.content) }
                            .map { embedding ->
                                ResumeChunkDocument(
                                    id = chunk.id,
                                    type = chunk.type,
                                    content = chunk.content,
                                    contentEmbedding = embedding,
                                    company = chunk.company,
                                    skills = chunk.skills,
                                    source = chunk.source
                                )
                            }
                            .doOnError { error -> logger.error("Failed to create embedding for chunk: ${chunk.id}. Skipping.", error) }
                            .onErrorResume { Mono.empty() }
                    }
            )
            .collectList()
            .flatMap { documents ->
                if (documents.isNotEmpty()) {
                    mongoTemplate.insertAll(documents).then(Mono.just(documents.size))
                } else {
                    Mono.just(0)
                }
            }
            .doOnSuccess { indexedCount ->
                logger.info("Successfully indexed $indexedCount resume chunks into MongoDB Atlas.")
            }
            .awaitSingle()
    }

    override suspend fun searchSimilarResumeSections(query: String, topK: Int, filter: Document?): List<String> {
        val queryEmbedding = embeddingPort.embedContent(query)
        if (queryEmbedding.isEmpty()) {
            logger.warn("Query embedding failed. Returning empty search results.")
            return emptyList()
        }
        val vectorSearchStage = Aggregation.stage(
            Document("\$vectorSearch",
                Document("index", VECTOR_INDEX_NAME)
                    .append("path", "content_embedding")
                    .append("queryVector", queryEmbedding)
                    .append("numCandidates", (topK * NUM_CANDIDATES_MULTIPLIER).toLong())
                    .append("limit", topK.toLong())
                    .apply { filter?.let { append("filter", it) } }
            )
        )
        val projectStage = Aggregation.project("content").andExclude("_id")

        val aggregation: TypedAggregation<ResumeChunkDocument> = Aggregation.newAggregation(
            ResumeChunkDocument::class.java,
            vectorSearchStage,
            projectStage
        )

        return mongoTemplate.aggregate(aggregation, COLLECTION_NAME, Document::class.java)
            .mapNotNull { it.getString("content") }
            .collectList()
            .awaitSingleOrNull() ?: emptyList()
    }

    override suspend fun findChunkById(id: String): String? {
        return mongoTemplate.findById<ResumeChunkDocument>(id)
            .map { it.content }
            .awaitSingleOrNull()
    }
}