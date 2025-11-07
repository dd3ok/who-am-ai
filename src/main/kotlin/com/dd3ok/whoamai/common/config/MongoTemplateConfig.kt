package com.dd3ok.whoamai.common.config

import com.mongodb.ConnectionString
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate

/**
 * 작업 목적: Spring AI VectorStore 자동 구성에서 요구하는 동기식 MongoTemplate Bean을 제공한다.
 * 주요 로직: 환경 변수 기반 MongoDB URI를 파싱해 MongoClient/MongoTemplate을 초기화하고, 기존 Reactive 설정과 병행 운용한다.
 */
@Configuration
class MongoTemplateConfig(
    @Value("\${spring.data.mongodb.uri}") private val mongoUri: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    @ConditionalOnMissingBean(MongoClient::class)
    fun mongoClient(): MongoClient {
        if (mongoUri.isBlank()) {
            throw IllegalStateException("spring.data.mongodb.uri 값이 비어 있어 MongoClient를 생성할 수 없습니다.")
        }
        logger.info("Initializing synchronous MongoClient for Spring AI VectorStore integration.")
        return MongoClients.create(mongoUri)
    }

    @Bean
    @ConditionalOnMissingBean(MongoTemplate::class)
    fun mongoTemplate(mongoClient: MongoClient): MongoTemplate {
        val connectionString = ConnectionString(mongoUri)
        val database = connectionString.database
            ?: throw IllegalStateException("MongoDB URI에 데이터베이스명이 포함되어야 합니다.")
        return MongoTemplate(mongoClient, database)
    }
}
