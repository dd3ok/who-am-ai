package com.dd3ok.whoamai.adapter.out.persistence

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MongoIndexWarmupRunnerTest {

    @Test
    fun `warms all targets on application ready without letting one failure block others`() = runTest {
        val succeeded = AtomicInteger(0)
        val runner = MongoIndexWarmupRunner(
            targets = listOf(
                MongoIndexWarmupTarget { succeeded.incrementAndGet() },
                MongoIndexWarmupTarget { error("mongo unavailable at startup") },
                MongoIndexWarmupTarget { withTimeout(1) { delay(100) } },
                MongoIndexWarmupTarget { succeeded.incrementAndGet() }
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        try {
            runner.onApplicationReady()
            assertEquals(2, succeeded.get())
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `no-ops cleanly when there are no warmup targets`() {
        val runner = MongoIndexWarmupRunner(emptyList())
        runner.onApplicationReady()
        runner.shutdown()
    }
}
