package com.dd3ok.whoamai.application.port.out

@Deprecated("Spring AI EmbeddingModel Bean 기반 구현으로 전환 예정")
interface EmbeddingPort {
    suspend fun embedContent(text: String): List<Float>
}
