package com.dd3ok.whoamai.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field

@Document(collection = MongoVectorAdapter.COLLECTION_NAME)
data class ResumeChunkDocument(
    @Id
    val id: String,

    @Field("chunk_type")
    val type: String,

    @Field("content_text")
    val content: String,

    @Field("content_embedding")
    val contentEmbedding: List<Float>,

    @Field("company")
    val company: String? = null,

    @Field("skills")
    val skills: List<String>? = emptyList(),

    @Field("source_data")
    val source: Map<String, Any>
)