package com.dd3ok.whoamai.application.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class RateLimiterService(
    @Value("\${rate-limit.requests}") private val maxRequests: Int,
    @Value("\${rate-limit.minutes}") private val windowInMinutes: Long,
    private val currentTimeProvider: () -> Long = System::currentTimeMillis,
    private val cleanupIntervalMillis: Long = windowInMinutes * 60 * 1000
) {
    private val requestCounts = ConcurrentHashMap<String, MutableList<Long>>()
    private val cleanupLock = Any()
    @Volatile
    private var lastCleanupTime: Long = 0L

    fun isAllowed(ip: String): Boolean {
        val currentTime = currentTimeProvider()
        val windowInMillis = windowInMinutes * 60 * 1000
        cleanupExpiredBuckets(currentTime, windowInMillis)

        var allowed = false
        requestCounts.compute(ip) { _, existingTimestamps ->
            val timestamps = existingTimestamps ?: mutableListOf()
            timestamps.removeIf { it < currentTime - windowInMillis }

            if (timestamps.size < maxRequests) {
                timestamps.add(currentTime)
                allowed = true
            }

            if (timestamps.isEmpty()) null else timestamps
        }
        return allowed
    }

    private fun cleanupExpiredBuckets(currentTime: Long, windowInMillis: Long) {
        if (cleanupIntervalMillis > 0 && currentTime - lastCleanupTime < cleanupIntervalMillis) {
            return
        }
        synchronized(cleanupLock) {
            if (cleanupIntervalMillis > 0 && currentTime - lastCleanupTime < cleanupIntervalMillis) {
                return
            }
            lastCleanupTime = currentTime

            requestCounts.keys.forEach { ip ->
                requestCounts.computeIfPresent(ip) { _, timestamps ->
                    timestamps.removeIf { it < currentTime - windowInMillis }
                    if (timestamps.isEmpty()) null else timestamps
                }
            }
        }
    }
}
