package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.adapter.out.persistence.ChatHistoryDocumentRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/healthcheck")
class HealthcheckController(
    private val chatHistoryRepository: ChatHistoryDocumentRepository
) {

    @GetMapping
    suspend fun healthcheck(): ResponseEntity<Long> {
        val count = chatHistoryRepository.count()
        return ResponseEntity.ok(count)
    }
}
