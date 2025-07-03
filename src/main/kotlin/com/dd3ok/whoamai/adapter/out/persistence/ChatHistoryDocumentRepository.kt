package com.dd3ok.whoamai.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ChatHistoryDocumentRepository : CoroutineCrudRepository<ChatHistoryDocument, String>