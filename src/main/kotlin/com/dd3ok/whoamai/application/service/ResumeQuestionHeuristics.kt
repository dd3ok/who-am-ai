package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import com.dd3ok.whoamai.domain.Resume

internal fun normalizeResumeQuery(text: String): String =
    text.lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

internal fun isLikelyOwnDomainQuestion(
    rawPrompt: String,
    normalizedPrompt: String,
    resume: Resume,
    nameFragments: Set<String> = NameFragmentExtractor.extract(resume.name)
): Boolean {
    if (isOwnServiceQuestion(normalizedPrompt)) {
        return true
    }
    return isLikelyResumeQuestion(rawPrompt, normalizedPrompt, resume, nameFragments)
}

internal fun isOwnServiceQuestion(normalizedPrompt: String): Boolean {
    val hasNamedServiceTarget = OWN_SERVICE_NAMED_TOKENS.any { normalizedPrompt.contains(it) }
    val hasSelfServiceTarget = SELF_SERVICE_REFERENCE_TOKENS.any { normalizedPrompt.contains(it) }
    val hasImplementationIntent = SERVICE_IMPLEMENTATION_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasServiceIntroIntent = SERVICE_INTRO_KEYWORDS.any { normalizedPrompt.contains(it) }

    return (hasNamedServiceTarget && (hasImplementationIntent || hasServiceIntroIntent)) ||
        (hasSelfServiceTarget && hasImplementationIntent)
}

private fun isLikelyResumeQuestion(
    rawPrompt: String,
    normalizedPrompt: String,
    resume: Resume,
    nameFragments: Set<String> = NameFragmentExtractor.extract(resume.name)
): Boolean {
    if (!looksLikeQuestionOrRequest(rawPrompt, normalizedPrompt)) {
        return false
    }

    val hasNamedTarget = NameFragmentExtractor.matches(normalizedPrompt, nameFragments)
    val hasDirectTarget = hasNamedTarget ||
        DIRECT_TARGET_TOKENS.any { normalizedPrompt.contains(it) }
    val hasResumeEntity = hasResumeEntityHit(normalizedPrompt, resume)
    val hasExternalSubject = hasExternalNamedSubject(rawPrompt, nameFragments)
    val hasSelfContainedProfileSignal = SELF_CONTAINED_PROFILE_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasBroadCapabilitySignal = BROAD_CAPABILITY_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasDocumentSignal = DOCUMENT_SIGNAL_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasTargetedIntroSignal = hasNamedTarget &&
        TARGETED_INTRO_PATTERNS.any { normalizedPrompt.contains(it) }

    if (hasExternalSubject && !hasResumeEntity) {
        return false
    }

    return hasResumeEntity ||
        (hasSelfContainedProfileSignal && !hasExternalSubject) ||
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

private fun hasExternalNamedSubject(rawPrompt: String, nameFragments: Set<String>): Boolean {
    val trimmed = rawPrompt.trim()
    val match = Regex("^([\\p{L}\\p{Nd}\\-+.]+?)(은|는|이|가)").find(trimmed) ?: return false
    val subject = normalizeResumeQuery(match.groupValues[1])
        .removeSuffix(normalizeResumeQuery("님"))
        .removeSuffix(normalizeResumeQuery("씨"))

    if (subject.length < 2) return false
    if (nameFragments.any { fragment -> subject.contains(fragment) || fragment.contains(subject) }) return false
    if (OWN_SERVICE_NAMED_TOKENS.any { token -> subject.contains(token) || token.contains(subject) }) return false
    if (SELF_SERVICE_REFERENCE_TOKENS.any { token -> subject == token }) return false
    if (SELF_CONTAINED_PROFILE_KEYWORDS.any { subject.contains(it) }) return false
    if (DOCUMENT_SIGNAL_KEYWORDS.any { subject.contains(it) }) return false
    if (BROAD_CAPABILITY_KEYWORDS.any { subject.contains(it) }) return false
    return true
}

private val DIRECT_TARGET_TOKENS = listOf(
    "너", "넌", "너의", "니", "니가", "당신"
).map(::normalizeResumeQuery)

private val SELF_SERVICE_REFERENCE_TOKENS = listOf(
    "너는", "넌", "너의", "니가"
).map(::normalizeResumeQuery)

private val OWN_SERVICE_NAMED_TOKENS = listOf(
    "who-am-ai", "whoamai", "이 서비스", "이 앱", "이 챗봇", "레포", "repo", "깃허브", "github"
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

private val SERVICE_IMPLEMENTATION_KEYWORDS = listOf(
    "기술", "스택", "만들어", "만들었", "구현", "사용", "아키텍처",
    "프레임워크", "모델", "repo", "레포", "github", "깃허브"
).map(::normalizeResumeQuery)

private val SERVICE_INTRO_KEYWORDS = listOf(
    "뭐야", "무엇", "소개", "설명", "정체", "서비스"
).map(::normalizeResumeQuery)

private val QUESTION_OR_REQUEST_ENDINGS = listOf(
    "알려줘", "알려주세요", "말해줘", "말해주세요", "설명해줘", "설명해주세요",
    "정리해줘", "정리해주세요", "소개해줘", "소개해주세요", "요약해줘", "요약해주세요", "보여줘", "보여주세요",
    "뭐야", "뭐지", "누구야", "누구지", "몇년이야", "몇년있어", "궁금해", "궁금하다",
    "해줘", "있어", "인가", "인지", "했어", "하나요", "한가요", "인가요", "있나요",
    "사용하나요", "사용할수있나요", "쓸수있나요", "할수있나요"
).map(::normalizeResumeQuery)

private val QUESTION_OR_REQUEST_CONTAINS = listOf(
    "알수있", "무슨역할", "어떤일", "몇년", "얼마나", "무엇", "뭐가", "뭐를",
    "어떤", "무슨", "사용할수있", "쓸수있"
).map(::normalizeResumeQuery)

private val TARGETED_INTRO_PATTERNS = listOf(
    "에대해알려", "소개해", "누구야", "누구지", "누구세요"
).map(::normalizeResumeQuery)
