package com.dd3ok.whoamai.common.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals

class MongoUriPropertyConfigTest {

    @Test
    fun `mongo uri is configured for boot and data mongo properties`() {
        val yaml = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application.yml"))
        }.`object` ?: error("application.yml was not loaded")

        assertEquals("\${MONGO_URI:}", yaml.getProperty("spring.mongodb.uri"))
        assertEquals("\${MONGO_URI:}", yaml.getProperty("spring.data.mongodb.uri"))
    }
}
