package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ManageResumeUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class ResumeAdminController(
    private val manageResumeUseCase: ManageResumeUseCase
) {

    @PostMapping("/resume/reindex")
    suspend fun reindex(): ResponseEntity<String> {
        val resultMessage = manageResumeUseCase.reindexResumeData()
        return if (resultMessage.contains("finished")) {
            ResponseEntity.ok(resultMessage)
        } else {
            ResponseEntity.internalServerError().body(resultMessage)
        }
    }
}