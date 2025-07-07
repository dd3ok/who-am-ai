package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.application.service.dto.RouteDecision
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * RAG 파이프라인을 위해 관련성 높은 컨텍스트(문서 조각)를 검색하는 책임을 가집니다.
 * LLMRouter의 결정에 따라 검색 필터를 동적으로 구성합니다.
 */
@Component
class ContextRetriever(
    private val resumePersistencePort: ResumePersistencePort
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun retrieve(userPrompt: String, routeDecision: RouteDecision): List<String> {
        val filters = mutableListOf<Document>()

        routeDecision.company?.let {
            filters.add(Document("company", Document("\$eq", it)))
        }

        routeDecision.skills?.takeIf { it.isNotEmpty() }?.let {
            filters.add(Document("skills", Document("\$in", it)))
        }

        // 여러 필터를 AND 조건으로 결합
        val finalFilter = if (filters.isNotEmpty()) {
            Document("\$and", filters)
        } else {
            null
        }

        val searchKeywords = routeDecision.keywords?.joinToString(" ") ?: ""
        val finalQuery = "$userPrompt $searchKeywords".trim()

        logger.info("Performing vector search with query: '$finalQuery' and filter: $finalFilter")

        return resumePersistencePort.searchSimilarSections(finalQuery, topK = 3, filter = finalFilter)
    }
}