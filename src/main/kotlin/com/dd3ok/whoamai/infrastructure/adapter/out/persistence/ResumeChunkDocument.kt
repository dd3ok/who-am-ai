package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field

@Document(collection = "resume_chunks")
data class ResumeChunkDocument(
    @Id val id: String,
    val content: String,
    @Field("contentEmbedding") val contentEmbedding: List<Float>
)