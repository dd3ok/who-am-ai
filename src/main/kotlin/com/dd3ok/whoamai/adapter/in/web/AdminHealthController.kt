package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.service.DeepHealthReport
import com.dd3ok.whoamai.application.service.DeepHealthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/health")
class AdminHealthController(
    private val deepHealthService: DeepHealthService
) {

    @GetMapping("/deep")
    suspend fun deepHealth(): ResponseEntity<DeepHealthReport> {
        val report = deepHealthService.check()
        return if (report.status == "UP") {
            ResponseEntity.ok(report)
        } else {
            ResponseEntity.status(503).body(report)
        }
    }
}
