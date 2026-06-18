package com.dd3ok.whoamai.application.service.agent

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.domain.Resume
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CareerAgentMetricsTest {

    @Test
    fun `career tool metrics record tool names`() {
        val registry = SimpleMeterRegistry()
        val tools = CareerAgentTools(SampleResumeProviderPort(), CareerAgentToolFormatter(), registry)

        tools.getRecentActivities()

        assertEquals(
            1.0,
            registry.counter("whoamai.agent.tool.call.total", "tool", "get_recent_activities").count()
        )
    }

    private class SampleResumeProviderPort : ResumeProviderPort {
        override fun getResume(): Resume = Resume(
            name = "유인재",
            recentActivities = "AI 에이전트와 백엔드 시스템 설계를 학습하고 있습니다."
        )

        override fun isInitialized(): Boolean = true
    }
}
