package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.port.out.ChatAnswerCacheStore
import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import com.dd3ok.whoamai.application.service.LLMRouter
import com.dd3ok.whoamai.application.service.OwnDomainProfileProvider
import com.dd3ok.whoamai.application.service.containsResumeTerm
import com.dd3ok.whoamai.application.service.isOwnServiceQuestion
import com.dd3ok.whoamai.application.service.normalizeResumeQuery
import com.dd3ok.whoamai.common.config.ChatCacheProperties
import com.dd3ok.whoamai.common.util.NameFragmentExtractor
import com.dd3ok.whoamai.domain.CachedChatAnswer
import com.dd3ok.whoamai.domain.ChatMessage
import com.dd3ok.whoamai.domain.ChatSource
import com.dd3ok.whoamai.domain.PromptCacheKey
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ChatAnswerCacheCoordinator(
    private val cacheStore: ChatAnswerCacheStore,
    private val resumeProviderPort: ResumeProviderPort,
    private val ownDomainProfileProvider: OwnDomainProfileProvider,
    private val llmRouter: LLMRouter,
    private val meterRegistry: MeterRegistry,
    private val properties: ChatCacheProperties = ChatCacheProperties()
) {
    sealed interface LookupResult {
        data object Bypass : LookupResult
        data class Hit(val entry: CachedChatAnswer) : LookupResult
        data class Miss(
            val cacheKey: String,
            val normalizedPrompt: String,
            val resumeVersion: String
        ) : LookupResult
    }

    suspend fun lookup(userPrompt: String, pastHistory: List<ChatMessage>): LookupResult {
        if (!isCacheEligibleQuery(userPrompt, pastHistory)) {
            meterRegistry.counter("whoamai.chat.cache.total", "result", "bypass").increment()
            return LookupResult.Bypass
        }

        val normalizedPrompt = PromptCacheKey.normalize(userPrompt)
        val version = currentResumeVersion()
        val cacheKey = PromptCacheKey.build(normalizedPrompt, version)
        val cached = cacheStore.find(cacheKey)
        if (cached != null) {
            meterRegistry.counter("whoamai.chat.cache.total", "result", "hit").increment()
            return LookupResult.Hit(cached)
        }

        meterRegistry.counter("whoamai.chat.cache.total", "result", "miss").increment()
        return LookupResult.Miss(cacheKey, normalizedPrompt, version)
    }

    suspend fun saveIfCacheable(
        lookup: LookupResult,
        rawPrompt: String,
        plan: CareerContextPlan,
        answerText: String
    ) {
        if (lookup !is LookupResult.Miss) return
        if (answerText.isBlank()) return
        if (!plan.prompt.useRagPrompt) return
        if (plan.prompt.contexts.none { it.isNotBlank() }) return
        if (plan.prompt.userPrompt != rawPrompt) return

        val sources = plan.prompt.sources.map { ChatSource(it.chunkId, it.title, it.url) }
        val entry = CachedChatAnswer(
            cacheKey = lookup.cacheKey,
            normalizedPrompt = lookup.normalizedPrompt,
            answerText = answerText,
            sources = sources,
            resumeVersion = lookup.resumeVersion
        )
        cacheStore.save(entry, Instant.now().plusSeconds(properties.ttlSeconds.coerceAtLeast(60L)))
    }

    internal fun isCacheEligibleQuery(userPrompt: String, pastHistory: List<ChatMessage>): Boolean {
        if (!properties.enabled) return false
        val trimmed = userPrompt.trim()
        if (trimmed.length < 6 || trimmed.length > 300) return false

        val normalizedForGuard = normalizeResumeQuery(trimmed)
        if (normalizedForGuard.length < 4) return false

        if (pastHistory.isEmpty()) {
            return true
        }

        if (hasHistoryReference(trimmed, normalizedForGuard, pastHistory)) {
            return false
        }

        // Only reuse cache mid-conversation when the question carries its own explicit subject
        // and does not get rewritten by conversational history.
        if (llmRouter.resolveQuery(trimmed, pastHistory) != trimmed) {
            return false
        }

        return isExplicitStandaloneSubject(trimmed, normalizedForGuard)
    }

    private fun hasHistoryReference(rawPrompt: String, normalizedPrompt: String, history: List<ChatMessage>): Boolean {
        if (CONTEXT_REFERENCE_PATTERNS.any { rawPrompt.contains(it, ignoreCase = true) }) {
            return true
        }
        return normalizedPrompt.length >= 24 && history.takeLast(12).any {
            it.role in setOf("model", "assistant") && normalizeResumeQuery(it.text).contains(normalizedPrompt)
        }
    }

    private fun isExplicitStandaloneSubject(rawPrompt: String, normalizedPrompt: String): Boolean {
        if (isOwnServiceQuestion(normalizedPrompt)) return true

        val resume = resumeProviderPort.getResume()
        val companyHit = resume.experiences.any { exp ->
            containsResumeTerm(rawPrompt, exp.company) || exp.aliases.any { containsResumeTerm(rawPrompt, it) }
        }
        if (companyHit) return true

        val projectHit = resume.projects.any { containsResumeTerm(rawPrompt, it.title) }
        if (projectHit) return true

        val ownerMatch = NameFragmentExtractor.matches(normalizedPrompt, NameFragmentExtractor.extract(resume.name))
        if (ownerMatch) return true

        return STANDALONE_PROFILE_PATTERNS.any { normalizedPrompt.contains(it) }
    }

    private fun currentResumeVersion(): String {
        val resumeSnapshot = resumeProviderPort.getResume().toString()
        val serviceProfile = ownDomainProfileProvider.serviceProfile()
        return PromptCacheKey.sha256("$resumeSnapshot|$serviceProfile")
    }

    companion object {
        private val CONTEXT_REFERENCE_PATTERNS = listOf(
            "방금", "아까", "이전", "앞서", "앞선", "거기", "그때", "그거", "그것", "이거", "이것",
            "해당 ", "둘 다", "비교", "차이", "이어서", "더 자세히", "추가로", "좀 더",
            "말고", "대신", "외에", "제외하고"
        )

        private val STANDALONE_PROFILE_PATTERNS = listOf(
            "보유한주요기술스택",
            "총경력기간",
            "학력",
            "자격증",
            "공개된개인github프로젝트",
            "공개github프로젝트",
            "최근진행한프로젝트"
        )
    }
}
