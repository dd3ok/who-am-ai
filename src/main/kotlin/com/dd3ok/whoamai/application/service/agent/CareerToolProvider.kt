package com.dd3ok.whoamai.application.service.agent

import org.springframework.stereotype.Component

interface CareerToolProvider {
    fun tools(): Array<Any>
}

@Component
class SpringCareerToolProvider(
    private val careerAgentTools: CareerAgentTools
) : CareerToolProvider {
    override fun tools(): Array<Any> = arrayOf(careerAgentTools)
}
