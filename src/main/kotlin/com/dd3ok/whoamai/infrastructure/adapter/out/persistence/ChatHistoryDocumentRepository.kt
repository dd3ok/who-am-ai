package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ChatHistoryDocumentRepository : CoroutineCrudRepository<ChatHistoryDocument, String>