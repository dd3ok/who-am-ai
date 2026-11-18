package com.dd3ok.whoamai.adapter.out.persistence

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 기동 직후 백그라운드에서 MongoDB 연결 풀과 TTL 인덱스를 사전 준비하는 대상.
 */
fun interface MongoIndexWarmupTarget {
    suspend fun warmUp()
}

/**
 * 애플리케이션 기동 완료(ApplicationReadyEvent) 후 비동기로 MongoDB 인덱스를 워밍한다.
 * 헬스체크/기동을 블로킹하지 않으며, 실패하더라도 첫 실제 요청의 ensureIndexes()가 안전하게 재시도한다.
 */
@Component
class MongoIndexWarmupRunner @Autowired constructor(
    private val targets: List<MongoIndexWarmupTarget>
) {
    internal constructor(
        targets: List<MongoIndexWarmupTarget>,
        dispatcher: CoroutineDispatcher
    ) : this(targets) {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (targets.isEmpty()) return
        scope.launch { warmUpAll() }
    }

    internal suspend fun warmUpAll() = supervisorScope {
        for (target in targets) {
            launch { warmSafely(target) }
        }
    }

    private suspend fun warmSafely(target: MongoIndexWarmupTarget) {
        try {
            target.warmUp()
        } catch (e: TimeoutCancellationException) {
            logger.warn("Background MongoDB warmup timed out for {}. First request will retry.", target::class.simpleName, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Background MongoDB warmup skipped for {}. First request will retry.", target::class.simpleName, e)
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }
}
