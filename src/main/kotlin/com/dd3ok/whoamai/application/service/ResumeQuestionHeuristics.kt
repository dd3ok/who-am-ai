package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import com.dd3ok.whoamai.domain.Resume

internal fun normalizeResumeQuery(text: String): String =
    text.lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

internal fun isLikelyResumeQuestion(
    rawPrompt: String,
    normalizedPrompt: String,
    resume: Resume,
    nameFragments: Set<String> = NameFragmentExtractor.extract(resume.name)
): Boolean {
    if (!looksLikeQuestionOrRequest(rawPrompt, normalizedPrompt)) {
        return false
    }

    val hasDirectTarget = NameFragmentExtractor.matches(normalizedPrompt, nameFragments) ||
        DIRECT_TARGET_TOKENS.any { normalizedPrompt.contains(it) }
    val hasResumeEntity = hasResumeEntityHit(normalizedPrompt, resume)
    val hasSelfContainedProfileSignal = SELF_CONTAINED_PROFILE_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasBroadCapabilitySignal = BROAD_CAPABILITY_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasDocumentSignal = DOCUMENT_SIGNAL_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasTargetedIntroSignal = hasDirectTarget &&
        TARGETED_INTRO_PATTERNS.any { normalizedPrompt.contains(it) }

    return hasResumeEntity ||
        hasSelfContainedProfileSignal ||
        hasTargetedIntroSignal ||
        (hasBroadCapabilitySignal && (hasDirectTarget || hasResumeEntity)) ||
        (hasDocumentSignal && (hasDirectTarget || hasResumeEntity))
}

private fun looksLikeQuestionOrRequest(rawPrompt: String, normalizedPrompt: String): Boolean {
    val trimmed = rawPrompt.trim()
    if (trimmed.contains('?') || trimmed.contains('？')) {
        return true
    }
    return QUESTION_OR_REQUEST_ENDINGS.any { normalizedPrompt.endsWith(it) } ||
        QUESTION_OR_REQUEST_CONTAINS.any { normalizedPrompt.contains(it) }
}

private fun hasResumeEntityHit(normalizedPrompt: String, resume: Resume): Boolean {
    val experienceHit = resume.experiences.any { exp ->
        normalizedPrompt.contains(normalizeResumeQuery(exp.company)) ||
            exp.aliases.any { alias -> normalizedPrompt.contains(normalizeResumeQuery(alias)) }
    }
    if (experienceHit) return true

    val projectHit = resume.projects.any { project ->
        normalizedPrompt.contains(normalizeResumeQuery(project.title)) ||
            normalizedPrompt.contains(normalizeResumeQuery(project.company)) ||
            project.skills.any { skill -> normalizedPrompt.contains(normalizeResumeQuery(skill)) } ||
            project.tags.any { tag -> normalizedPrompt.contains(normalizeResumeQuery(tag)) }
    }
    if (projectHit) return true

    val skillHit = resume.skills.any { normalizedPrompt.contains(normalizeResumeQuery(it)) }
    if (skillHit) return true

    return resume.education.any { edu ->
        normalizedPrompt.contains(normalizeResumeQuery(edu.school)) ||
            normalizedPrompt.contains(normalizeResumeQuery(edu.major))
    }
}

private val DIRECT_TARGET_TOKENS = listOf(
    "너", "넌", "너의", "니", "니가", "당신"
).map(::normalizeResumeQuery)

private val SELF_CONTAINED_PROFILE_KEYWORDS = listOf(
    "경력", "직무", "회사", "프로젝트", "스킬", "기술", "자격증", "학력", "학교", "전공",
    "mbti", "관심사", "관심", "관심있는", "흥미", "취미",
    "총경력", "전체경력"
).map(::normalizeResumeQuery)

private val BROAD_CAPABILITY_KEYWORDS = listOf(
    "소개", "역할", "책임", "성과", "강점", "경험", "리딩", "협업", "커뮤니케이션",
    "트러블슈팅", "성능", "장애", "개선", "최적화", "아키텍처", "msa", "ddd",
    "자동화", "배치", "테스트", "품질", "보안", "인증", "인가", "학습"
).map(::normalizeResumeQuery)

private val DOCUMENT_SIGNAL_KEYWORDS = listOf(
    "이력서", "포트폴리오", "요약"
).map(::normalizeResumeQuery)

private val QUESTION_OR_REQUEST_ENDINGS = listOf(
    "알려줘", "알려주세요", "말해줘", "말해주세요", "설명해줘", "설명해주세요",
    "정리해줘", "정리해주세요", "소개해줘", "소개해주세요", "요약해줘", "요약해주세요", "보여줘", "보여주세요",
    "뭐야", "뭐지", "누구야", "누구지", "몇년이야", "몇년있어", "궁금해", "궁금하다",
    "해줘", "있어", "인가", "인지", "했어"
).map(::normalizeResumeQuery)

private val QUESTION_OR_REQUEST_CONTAINS = listOf(
    "알수있", "무슨역할", "어떤일", "몇년", "얼마나", "무엇", "뭐가", "뭐를"
).map(::normalizeResumeQuery)

private val TARGETED_INTRO_PATTERNS = listOf(
    "에대해알려", "소개해", "누구야", "누구지", "누구세요"
).map(::normalizeResumeQuery)
