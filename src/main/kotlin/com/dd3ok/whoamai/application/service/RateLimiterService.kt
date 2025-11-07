package com.dd3ok.whoamai.application.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class RateLimiterService(
    @Value("\${rate-limit.requests}") private val maxRequests: Int,
    @Value("\${rate-limit.minutes}") private val windowInMinutes: Long
) {
    // Key: IP 주소, Value: 요청 타임스탬프(Long) 목록
    private val requestCounts = ConcurrentHashMap<String, MutableList<Long>>()

    fun isAllowed(ip: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val windowInMillis = windowInMinutes * 60 * 1000

        // 해당 IP에 대한 요청 기록을 가져오거나 새로 생성 (스레드 안전)
        val timestamps = requestCounts.computeIfAbsent(ip) { mutableListOf() }

        // 동기화 블록으로 리스트 접근을 보호
        synchronized(timestamps) {
            // 10분 윈도우에서 벗어난 오래된 타임스탬프 제거
            timestamps.removeIf { it < currentTime - windowInMillis }

            // 현재 요청 횟수가 최대치를 초과했는지 확인
            if (timestamps.size >= maxRequests) {
                return false // 제한 초과
            }

            // 허용된 요청이므로 현재 타임스탬프 추가
            timestamps.add(currentTime)
            return true // 허용
        }
    }
}