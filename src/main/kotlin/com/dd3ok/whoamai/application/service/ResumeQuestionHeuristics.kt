package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import com.dd3ok.whoamai.domain.Resume

internal fun normalizeResumeQuery(text: String): String =
    text.lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

/** Keep Latin word boundaries: .NET must not match Kubernetes, nor Java match JavaScript. */
internal fun containsResumeTerm(query: String, term: String): Boolean {
    if (term.isBlank()) return false
    if (term.none { it.lowercaseChar() in 'a'..'z' }) {
        return normalizeResumeQuery(query).contains(normalizeResumeQuery(term))
    }
    val pattern = Regex("[\\p{L}\\p{Nd}]+").findAll(term.lowercase())
        .joinToString("[\\s._-]*") { Regex.escape(it.value) }
    return pattern.isNotEmpty() && Regex("(?<![a-z])$pattern(?![a-z])", RegexOption.IGNORE_CASE).containsMatchIn(query)
}

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
    val hasResumeEntity = hasResumeEntityHit(rawPrompt, resume)
    val hasOwnWorkTarget = resume.experiences.any { exp ->
        containsResumeTerm(rawPrompt, exp.company) || exp.aliases.any { containsResumeTerm(rawPrompt, it) }
    } || resume.projects.any { containsResumeTerm(rawPrompt, it.title) }
    val hasExternalSubject = hasExternalNamedSubject(rawPrompt, nameFragments)
    val hasSelfContainedProfileSignal = SELF_CONTAINED_PROFILE_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasBroadCapabilitySignal = BROAD_CAPABILITY_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasProfileCapabilitySignal = listOf("강점", "성과", "경험", "협업방식").any { normalizedPrompt.contains(it) } &&
        !listOf("공부", "작성", "추천", "방법").any { normalizedPrompt.contains(it) }
    val hasDocumentSignal = DOCUMENT_SIGNAL_KEYWORDS.any { normalizedPrompt.contains(it) }
    val hasTargetedIntroSignal = hasNamedTarget &&
        TARGETED_INTRO_PATTERNS.any { normalizedPrompt.contains(it) }

    if (hasExternalSubject && !hasOwnWorkTarget) {
        return false
    }
    if (!hasDirectTarget && !hasOwnWorkTarget &&
        listOf("공부", "작성", "추천", "방법").any { normalizedPrompt.contains(it) }
    ) return false

    return hasResumeEntity ||
        (hasSelfContainedProfileSignal && !hasExternalSubject) ||
        hasTargetedIntroSignal ||
        (hasProfileCapabilitySignal && !hasExternalSubject) ||
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

private fun hasResumeEntityHit(rawPrompt: String, resume: Resume): Boolean {
    val experienceHit = resume.experiences.any { exp ->
        containsResumeTerm(rawPrompt, exp.company) ||
            exp.aliases.any { alias -> containsResumeTerm(rawPrompt, alias) }
    }
    if (experienceHit) return true

    val projectHit = resume.projects.any { project ->
        containsResumeTerm(rawPrompt, project.title) ||
            containsResumeTerm(rawPrompt, project.company) ||
            project.skills.any { skill -> containsResumeTerm(rawPrompt, skill) } ||
            project.tags.any { tag -> containsResumeTerm(rawPrompt, tag) }
    }
    if (projectHit) return true

    val skillHit = resume.skills.any { containsResumeTerm(rawPrompt, it) }
    if (skillHit) return true

    return resume.education.any { edu ->
        containsResumeTerm(rawPrompt, edu.school) ||
            containsResumeTerm(rawPrompt, edu.major)
    }
}

internal fun hasExternalNamedSubject(rawPrompt: String, nameFragments: Set<String>): Boolean {
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

internal fun isFollowUpQuestion(prompt: String): Boolean {
    val normalized = normalizeResumeQuery(prompt)
    return listOf(
        "그래서", "그럼", "거기", "그프로젝트", "그회사", "그때", "그중", "왜그렇게", "왜그걸",
        "더자세", "조금더", "자세히", "성과는", "역할은", "결과는", "이유는"
    ).any { normalized.startsWith(it) }
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
).map(::normalizeResumeQuery) + ResumeIntentKeywords.recentActivity

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
