package com.dd3ok.whoamai.application.port.out

import com.dd3ok.whoamai.domain.ChatMessage

enum class PortfolioSelectionIntent {
    PORTFOLIO_ANSWER,
    MISSING_EVIDENCE,
    GENERAL_CHAT
}

data class PortfolioCatalogEntry(
    val chunkId: String,
    val chunkType: String,
    val title: String,
    val summary: String,
    val keywords: List<String> = emptyList()
)

data class PortfolioSelection(
    val intent: PortfolioSelectionIntent,
    val chunkIds: List<String> = emptyList()
)

fun interface PortfolioCatalogSelectorPort {
    suspend fun selectContext(
        question: String,
        history: List<ChatMessage>,
        catalog: List<PortfolioCatalogEntry>
    ): PortfolioSelection?
}
