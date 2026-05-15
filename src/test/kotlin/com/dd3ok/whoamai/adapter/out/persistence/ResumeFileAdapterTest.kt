package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.domain.Resume
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStream

class ResumeFileAdapterTest {

    @Test
    fun `load should fail fast when resume json cannot be parsed`() {
        val objectMapper = object : ObjectMapper() {
            override fun <T : Any?> readValue(src: InputStream, valueType: Class<T>): T {
                throw RuntimeException("broken json")
            }
        }
        val adapter = ResumeFileAdapter(objectMapper)

        val exception = assertThrows<IllegalStateException> {
            adapter.load()
        }
        assertTrue(exception.message.orEmpty().contains("resume.json"))
    }
}
