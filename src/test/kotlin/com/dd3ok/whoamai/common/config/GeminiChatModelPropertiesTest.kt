package com.dd3ok.whoamai.common.config

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals

class GeminiChatModelPropertiesTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `binds custom Gemini chat fallback properties from app namespace`() {
        contextRunner
            .withPropertyValues(
                "who-am-ai.ai.chat.models[0]=gemini-3.1-flash-lite",
                "who-am-ai.ai.chat.models[1]=gemini-2.5-flash-lite",
                "who-am-ai.ai.chat.reasoning-effort=high",
                "who-am-ai.ai.chat.selector-reasoning-effort=minimal",
                "who-am-ai.ai.chat.max-output-tokens=4096",
                "who-am-ai.ai.chat.rate-limit-cooldown-ms=45000"
            )
            .run { context ->
                val properties = context.getBean(GeminiChatModelProperties::class.java)

                assertEquals(listOf("gemini-3.1-flash-lite", "gemini-2.5-flash-lite"), properties.models)
                assertEquals("high", properties.reasoningEffort)
                assertEquals("minimal", properties.selectorReasoningEffort)
                assertEquals(4096, properties.maxOutputTokens)
                assertEquals(45_000L, properties.rateLimitCooldownMs)
            }
    }

    @Test
    fun `binds custom Gemini decision properties from app namespace`() {
        ApplicationContextRunner()
            .withUserConfiguration(DecisionTestConfig::class.java)
            .withPropertyValues(
                "who-am-ai.ai.decision.enabled=false",
                "who-am-ai.ai.decision.base-url=http://127.0.0.1:8089/v1beta",
                "who-am-ai.ai.decision.api-key=decision-prop-test",
                "who-am-ai.ai.decision.model=gemini-decision-1",
                "who-am-ai.ai.decision.timeout-ms=3200",
                "who-am-ai.ai.decision.topic-confidence-threshold=0.65",
                "who-am-ai.ai.decision.chunk-noul-threshold=0.55",
                "who-am-ai.ai.decision.chunk-fallback-noul-threshold=0.40",
                "who-am-ai.ai.decision.chunk-high-confidence-rescue-threshold=0.90",
                "who-am-ai.ai.decision.max-selected-chunks=6"
            )
            .run { context ->
                val properties = context.getBean(GeminiDecisionProperties::class.java)

                assertEquals(false, properties.enabled)
                assertEquals("http://127.0.0.1:8089/v1beta", properties.baseUrl)
                assertEquals("decision-prop-test", properties.apiKey)
                assertEquals("gemini-decision-1", properties.model)
                assertEquals(3200L, properties.timeoutMs)
                assertEquals(0.65, properties.topicConfidenceThreshold)
                assertEquals(0.55, properties.chunkNoulThreshold)
                assertEquals(0.40, properties.chunkFallbackNoulThreshold)
                assertEquals(0.90, properties.chunkHighConfidenceRescueThreshold)
                assertEquals(6, properties.maxSelectedChunks)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeminiChatModelProperties::class)
    private class TestConfig

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeminiDecisionProperties::class)
    private class DecisionTestConfig
}
