package com.dd3ok.whoamai.application.service.dto

enum class QueryType {
    NON_RAG,   // 일반 대화 (이전 CHIT_CHAT + GENERAL_CONVERSATION)
    RESUME_RAG // 이력서 정보 질문
}