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
                "who-am-ai.ai.chat.temperature=0.75",
                "who-am-ai.ai.chat.max-output-tokens=4096",
                "who-am-ai.ai.chat.rate-limit-cooldown-ms=45000"
            )
            .run { context ->
                val properties = context.getBean(GeminiChatModelProperties::class.java)

                assertEquals(listOf("gemini-3.1-flash-lite", "gemini-2.5-flash-lite"), properties.models)
                assertEquals(0.75f, properties.temperature)
                assertEquals(4096, properties.maxOutputTokens)
                assertEquals(45_000L, properties.rateLimitCooldownMs)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeminiChatModelProperties::class)
    private class TestConfig
}
