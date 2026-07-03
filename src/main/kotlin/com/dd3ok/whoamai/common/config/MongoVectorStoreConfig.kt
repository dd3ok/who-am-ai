package com.dd3ok.whoamai.common.config

import org.springframework.ai.embedding.BatchingStrategy
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.TokenCountBatchingStrategy
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore
import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.data.mongodb.core.MongoTemplate

/**
 * 작업 목적: Render cold start 중 MongoDB Atlas VectorStore 초기화를 실제 RAG 요청 시점까지 늦춘다.
 * 주요 로직: Spring AI 자동 구성과 같은 옵션으로 VectorStore Bean을 만들되 lazy Bean으로 등록한다.
 */
@Configuration
@EnableConfigurationProperties(MongoDBAtlasVectorStoreProperties::class)
class MongoVectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(BatchingStrategy::class)
    fun batchingStrategy(): BatchingStrategy = TokenCountBatchingStrategy()

    @Bean
    @Lazy
    @ConditionalOnMissingBean(MongoDBAtlasVectorStore::class)
    fun mongoDBAtlasVectorStore(
        mongoTemplate: MongoTemplate,
        embeddingModel: EmbeddingModel,
        properties: MongoDBAtlasVectorStoreProperties,
        batchingStrategy: BatchingStrategy
    ): MongoDBAtlasVectorStore {
        val builder = MongoDBAtlasVectorStore.builder(mongoTemplate, embeddingModel)

        builder.initializeSchema(properties.isInitializeSchema)
        builder.batchingStrategy(batchingStrategy)
        properties.collectionName?.takeIf { it.isNotBlank() }?.let { builder.collectionName(it) }
        properties.pathName?.takeIf { it.isNotBlank() }?.let { builder.pathName(it) }
        properties.indexName?.takeIf { it.isNotBlank() }?.let { builder.vectorIndexName(it) }
        properties.metadataFieldsToFilter
            .takeIf { it.isNotEmpty() }
            ?.let { builder.metadataFieldsToFilter(it) }

        return builder.build()
    }
}
