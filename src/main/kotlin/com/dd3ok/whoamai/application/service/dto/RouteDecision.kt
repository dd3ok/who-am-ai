package com.dd3ok.whoamai.application.service.dto

data class RouteDecision(
    val queryType: QueryType,
    val company: String? = null,
    val skills: List<String>? = null,
    val keywords: List<String>? = null
)