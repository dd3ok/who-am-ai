package com.dd3ok.whoamai.adapter.`in`.web

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class HealthcheckControllerTest {

    @Test
    fun `healthcheck returns lightweight ok response`() = runTest {
        val controller = HealthcheckController()

        val response = controller.healthcheck()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(mapOf("status" to "ok"), response.body)
    }
}
