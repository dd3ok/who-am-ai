package com.dd3ok.whoamai.application.port.out

interface EmbeddingPort {
    suspend fun embedContent(text: String): List<Float>
}