package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.service.DeepHealthComponent
import com.dd3ok.whoamai.application.service.DeepHealthReport
import com.dd3ok.whoamai.application.service.DeepHealthService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class AdminHealthControllerTest {

    @Test
    fun `deep health returns ok when all components are up`() = runTest {
        val controller = AdminHealthController(
            deepHealthService = StubDeepHealthService(
                DeepHealthReport(
                    status = "UP",
                    components = mapOf("mongo" to DeepHealthComponent("UP", "reachable"))
                )
            )
        )

        val response = controller.deepHealth()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("UP", response.body?.status)
    }

    @Test
    fun `deep health returns service unavailable when any component is down`() = runTest {
        val controller = AdminHealthController(
            deepHealthService = StubDeepHealthService(
                DeepHealthReport(
                    status = "DOWN",
                    components = mapOf("mongo" to DeepHealthComponent("DOWN", "not configured"))
                )
            )
        )

        val response = controller.deepHealth()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("DOWN", response.body?.status)
    }

    private class StubDeepHealthService(
        private val report: DeepHealthReport
    ) : DeepHealthService {
        override suspend fun check(): DeepHealthReport = report
    }
}
