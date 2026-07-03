package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.LoadResumePort
import com.dd3ok.whoamai.domain.Resume
import tools.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class ResumeFileAdapter(private val objectMapper: ObjectMapper) : LoadResumePort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun load(): Resume {
        try {
            logger.info("Loading resume data from 'resume.json' file.")
            val resource = ClassPathResource("resume.json")
            if (!resource.exists()) {
                throw IllegalStateException("resume.json file not found.")
            }
            return objectMapper.readValue(resource.inputStream, Resume::class.java)
        } catch (e: Exception) {
            logger.error("FATAL: Failed to load and parse resume.json.", e)
            throw IllegalStateException("Failed to load and parse resume.json.", e)
        }
    }
}
