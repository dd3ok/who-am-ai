package com.dd3ok.whoamai.application.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimiterServiceTest {

    @Test
    fun `requests over limit are rejected for the same client`() {
        val service = RateLimiterService(maxRequests = 2, windowInMinutes = 1)

        assertTrue(service.isAllowed("10.0.0.1"))
        assertTrue(service.isAllowed("10.0.0.1"))
        assertFalse(service.isAllowed("10.0.0.1"))
    }

    @Test
    fun `stale client buckets are evicted during cleanup`() {
        var now = 0L
        val service = RateLimiterService(
            maxRequests = 1,
            windowInMinutes = 1,
            currentTimeProvider = { now },
            cleanupIntervalMillis = 0L
        )

        assertTrue(service.isAllowed("10.0.0.1"))
        now = 61_000L
        assertTrue(service.isAllowed("10.0.0.2"))

        assertEquals(setOf("10.0.0.2"), trackedClients(service))
    }

    private fun trackedClients(service: RateLimiterService): Set<String> {
        val field = RateLimiterService::class.java.getDeclaredField("requestCounts")
        field.isAccessible = true
        val map = field.get(service) as Map<*, *>
        return map.keys.map { it as String }.toSet()
    }
}
