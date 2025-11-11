package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.LoadResumePort
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.domain.Resume
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class ResumeDataProvider(
    private val loadResumePort: LoadResumePort
) : ResumeProviderPort {

    private lateinit var resume: Resume

    @PostConstruct
    fun initialize() {
        reload()
    }

    override fun getResume(): Resume {
        if (!isInitialized()) {
            throw IllegalStateException("Resume data is not initialized yet.")
        }
        return this.resume
    }

    override fun isInitialized(): Boolean = ::resume.isInitialized && resume.name.isNotBlank()

    override fun reload(): Resume {
        this.resume = loadResumePort.load()
        return this.resume
    }
}
