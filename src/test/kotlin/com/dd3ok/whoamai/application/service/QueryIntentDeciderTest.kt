package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.dto.IntentDecision
import com.dd3ok.whoamai.common.config.RoutingProperties
import com.dd3ok.whoamai.domain.Experience
import com.dd3ok.whoamai.domain.Period
import com.dd3ok.whoamai.domain.Resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryIntentDeciderTest {

    private val sampleResume = Resume(
        name = "유인재",
        skills = listOf("Spring Boot", "Kafka"),
        experiences = listOf(
            Experience(
                company = "지마켓",
                aliases = listOf("G마켓", "Gmarket"),
                period = Period("2021-01", "2024-01")
            )
        )
    )

    private val resumeProvider = object : ResumeProviderPort {
        override fun getResume(): Resume = sampleResume
        override fun isInitialized(): Boolean = true
        override fun reload(): Resume = sampleResume
    }

    @Test
    fun `non resume keyword forces general prompt`() {
        val decider = QueryIntentDecider(
            RoutingProperties(nonResumeKeywords = listOf("날씨")),
            resumeProvider
        )

        val decision = decider.decide("오늘 날씨 어때?")

        assertTrue(decision.useGeneralPrompt, "Weather keyword should route to general prompt")
    }

    @Test
    fun `company or alias keyword keeps resume intent`() {
        val decider = QueryIntentDecider(RoutingProperties(), resumeProvider)

        val decision = decider.decide("G마켓 프로젝트 알려줘")

        assertFalse(decision.useGeneralPrompt, "Company keyword should stay in resume mode")
        assertEquals("지마켓", decision.companyHint)
    }

    @Test
    fun `name fragment adds keyword hint`() {
        val decider = QueryIntentDecider(RoutingProperties(), resumeProvider)

        val decision = decider.decide("인재님 소개해주세요")

        assertFalse(decision.useGeneralPrompt)
        assertEquals(listOf("유인재"), decision.keywordHints)
    }
}
