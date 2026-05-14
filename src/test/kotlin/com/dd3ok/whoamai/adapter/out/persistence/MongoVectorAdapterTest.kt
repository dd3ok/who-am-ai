package com.dd3ok.whoamai.adapter.out.persistence

import com.mongodb.client.result.DeleteResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore
import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreProperties
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MongoVectorAdapterTest {

    @Test
    fun `indexResume adds new batch before removing stale chunks`() = runTest {
        val vectorStore = Mockito.mock(MongoDBAtlasVectorStore::class.java)
        val mongoTemplate = Mockito.mock(ReactiveMongoTemplate::class.java)
        val properties = MongoDBAtlasVectorStoreProperties().apply {
            collectionName = "test_chunks"
        }
        Mockito.`when`(mongoTemplate.count(Mockito.any(Query::class.java), Mockito.eq("test_chunks")))
            .thenReturn(Mono.just(1))
        Mockito.`when`(mongoTemplate.remove(Mockito.any(Query::class.java), Mockito.eq("test_chunks")))
            .thenReturn(Mono.just(DeleteResult.acknowledged(1)))
        val adapter = MongoVectorAdapter(vectorStore, properties, mongoTemplate, 0.65)

        val indexed = adapter.indexResume(
            listOf(
                ResumeChunk(
                    id = "summary",
                    type = "summary",
                    content = "요약",
                    source = emptyMap()
                )
            )
        )

        assertEquals(1, indexed)
        val inOrder = Mockito.inOrder(vectorStore, mongoTemplate)
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Document>>
        inOrder.verify(vectorStore).add(captor.capture())
        inOrder.verify(mongoTemplate).count(Mockito.any(Query::class.java), Mockito.eq("test_chunks"))
        inOrder.verify(mongoTemplate).remove(Mockito.any(Query::class.java), Mockito.eq("test_chunks"))
        val document = captor.value.first()
        assertEquals("summary", document.metadata["chunk_id"])
        assertTrue((document.metadata["index_batch_id"] as? String).orEmpty().isNotBlank())
    }

    @Test
    fun `indexResume does not remove stale chunks when new batch count is incomplete`() = runTest {
        val vectorStore = Mockito.mock(MongoDBAtlasVectorStore::class.java)
        val mongoTemplate = Mockito.mock(ReactiveMongoTemplate::class.java)
        val properties = MongoDBAtlasVectorStoreProperties().apply {
            collectionName = "test_chunks"
        }
        Mockito.`when`(mongoTemplate.count(Mockito.any(Query::class.java), Mockito.eq("test_chunks")))
            .thenReturn(Mono.just(0))
        val adapter = MongoVectorAdapter(vectorStore, properties, mongoTemplate, 0.65)

        assertThrows<IllegalStateException> {
            adapter.indexResume(
                listOf(
                    ResumeChunk(
                        id = "summary",
                        type = "summary",
                        content = "요약",
                        source = emptyMap()
                    )
                )
            )
        }

        Mockito.verify(mongoTemplate, Mockito.never())
            .remove(Mockito.any(Query::class.java), Mockito.eq("test_chunks"))
    }
}
