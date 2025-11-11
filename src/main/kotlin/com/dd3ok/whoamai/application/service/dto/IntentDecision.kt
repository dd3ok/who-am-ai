package com.dd3ok.whoamai.application.service.dto

data class IntentDecision(
    val useGeneralPrompt: Boolean,
    val companyHint: String? = null,
    val skillHints: List<String> = emptyList(),
    val keywordHints: List<String> = emptyList()
) {
    companion object {
        fun general(): IntentDecision = IntentDecision(useGeneralPrompt = true)
        fun resume(
            companyHint: String? = null,
            skillHints: List<String> = emptyList(),
            keywordHints: List<String> = emptyList()
        ) = IntentDecision(
            useGeneralPrompt = false,
            companyHint = companyHint,
            skillHints = skillHints,
            keywordHints = keywordHints
        )
    }
}
