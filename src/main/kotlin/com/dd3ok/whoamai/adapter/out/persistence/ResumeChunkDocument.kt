package com.dd3ok.whoamai.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field

/**
 * 작업 목적: Spring AI VectorStore가 저장한 MongoDB 문서를 도메인 조회 용도로 매핑한다.
 * 주요 로직: 컨텍스트 검색 시 `content` 필드를 추출하고, 필요 시 메타데이터를 추가 가공한다.
 */
@Document(collection = MongoVectorAdapter.COLLECTION_NAME)
data class ResumeChunkDocument(
    @Id
    val id: String,

    @Field("content")
    val content: String,

    @Field("metadata")
    val metadata: Map<String, Any> = emptyMap()
) {
    val company: String? get() = metadata["company"] as? String
    val skills: List<String> get() = (metadata["skills"] as? Collection<*>)?.mapNotNull { it as? String } ?: emptyList()
}
