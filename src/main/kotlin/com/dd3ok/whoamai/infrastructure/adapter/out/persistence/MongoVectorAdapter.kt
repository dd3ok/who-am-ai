package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.EmbeddingPort
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.TypedAggregation
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.jvm.java

@Component
class MongoVectorAdapter(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val embeddingPort: EmbeddingPort
) : VectorDBPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val COLLECTION_NAME = "resume_chunks"
        const val VECTOR_INDEX_NAME = "vector_index"
    }

    // Improved indexResume: Returns the count of indexed documents, using coroutines bridge
    override suspend fun indexResume(sections: Map<String, String>): Int {
        return mongoTemplate.dropCollection(ResumeChunkDocument::class.java)
            .thenMany(
                Flux.fromIterable(sections.entries)
                    .flatMap { (id, content) ->
                        mono {
                            embeddingPort.embedContent(content)
                        }.map { embedding ->
                            ResumeChunkDocument(id, content, embedding)
                        }
                            .doOnError { error ->
                                logger.error("Failed to create embedding for section: $id. Skipping.", error)
                            }
                            .onErrorResume { Mono.empty() }
                    }
            )
            .collectList()
            .flatMap { chunks ->
                if (chunks.isNotEmpty()) {
                    mongoTemplate.insertAll(chunks).then(Mono.just(chunks.size))
                } else {
                    Mono.just(0)
                }
            }
            .doOnSuccess { indexedCount ->
                logger.info("Successfully indexed $indexedCount resume sections into MongoDB Atlas.")
            }
            .awaitSingle() // awaitSingle는 값이 항상 있으므로, emptyList가 아니면 문제없음
        // (문제 상황에서는 예외가 발생함. 좀더 보수적이려면 awaitSingleOrNull() + ?: 0 도 OK)
    }

    override suspend fun searchSimilarResumeSections(query: String, topK: Int): List<String> {
        val queryEmbedding = embeddingPort.embedContent(query)

        val vectorSearchStage = Aggregation.stage(
            Document(
                "\$vectorSearch",
                Document("index", VECTOR_INDEX_NAME)
                    .append("path", "contentEmbedding")
                    .append("queryVector", queryEmbedding)
                    .append("numCandidates", (topK * 10).toLong())
                    .append("limit", topK.toLong())
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
}
