package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.domain.CachedChatAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.IndexDefinition
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MongoChatAnswerCacheAdapterTest {

    @Test
    fun `find falls back to null and save succeeds in memory when index setup times out`() = runTest {
        val template = Mockito.mock(ReactiveMongoTemplate::class.java)
        val indexOps = Mockito.mock(ReactiveIndexOperations::class.java)
        Mockito.`when`(template.indexOps(ChatAnswerCacheDocument::class.java)).thenReturn(indexOps)
        Mockito.`when`(indexOps.createIndex(Mockito.any(IndexDefinition::class.java))).thenReturn(Mono.never())

        val adapter = MongoChatAnswerCacheAdapter(template, indexSetupTimeoutMs = 20L)
        assertNull(adapter.find("missing-key"))

        val entry = CachedChatAnswer(
            cacheKey = "k1",
            normalizedPrompt = "지마켓 경력",
            answerText = "답변",
            sources = emptyList(),
            resumeVersion = "v1"
        )
        adapter.save(entry, Instant.now().plusSeconds(60))
        assertEquals(entry, adapter.find("k1"))
    }

    @Test
    fun `find still propagates caller cancellation`() = runTest {
        val template = Mockito.mock(ReactiveMongoTemplate::class.java)
        val indexOps = Mockito.mock(ReactiveIndexOperations::class.java)
        Mockito.`when`(template.indexOps(ChatAnswerCacheDocument::class.java)).thenReturn(indexOps)
        Mockito.`when`(indexOps.createIndex(Mockito.any(IndexDefinition::class.java)))
            .thenReturn(Mono.error(CancellationException("caller cancelled")))

        val adapter = MongoChatAnswerCacheAdapter(template, indexSetupTimeoutMs = 1_000L)
        assertFailsWith<CancellationException> {
            adapter.find("k2")
        }
    }
}
