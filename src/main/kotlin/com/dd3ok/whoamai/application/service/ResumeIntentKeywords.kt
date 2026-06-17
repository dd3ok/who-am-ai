package com.dd3ok.whoamai.application.service

internal object ResumeIntentKeywords {
    val recentActivity: List<String> = listOf(
        "퇴사 이후",
        "퇴사이후",
        "퇴사하고",
        "퇴사 후",
        "퇴사후",
        "요즘 뭐 하고",
        "요즘뭐하고",
        "요즘 뭐해",
        "요즘뭐해",
        "최근 뭐 하고",
        "최근뭐하고",
        "현재 뭐 하고",
        "현재뭐하고",
        "뭐하고 지내",
        "뭐하고지내",
        "근황",
        "공백기",
        "개인 프로젝트",
        "개인프로젝트"
    ).map(::normalizeResumeQuery).distinct()
}
