package com.dd3ok.whoamai.adapter.out.gemini

import com.dd3ok.whoamai.common.config.MongoVectorStoreConfig
import com.google.genai.Client
import com.google.genai.types.HttpOptions
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.ai.document.Document
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore
import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreProperties
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationResults
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Exercises the configured VectorStore, Spring AI and Google SDK against a local HTTP server. */
@Timeout(30)
class GeminiEmbeddingCompatibilityTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `indexing embeds each document separately and stores the original content`() = withServer { client, requests ->
        val mongo = Mockito.mock(MongoTemplate::class.java)
        contextRunner(client, mongo).run { context ->
            assertNull(context.startupFailure)
            val originals = listOf(
                Document("로그인 인증을 Spring Boot로 개편했습니다.", mapOf(
                    "source" to mapOf("title" to "ESM 로그인 개편"), "chunk_id" to "project_login",
                    "index_batch_id" to "batch-not-for-embedding"
                )),
                Document("보유 기술은 Kotlin, Redis입니다.", mapOf("chunk_id" to "skills"))
            )
            context.getBean(MongoDBAtlasVectorStore::class.java).add(originals)

            assertEquals(listOf(
                "title: ESM 로그인 개편 | text: ${originals[0].text}",
                "title: none | text: ${originals[1].text}"
            ), embeddedTexts(requests))
            assertRequestContract(requests)
            val saved = ArgumentCaptor.forClass(Any::class.java)
            Mockito.verify(mongo, Mockito.times(2)).save(saved.capture(), Mockito.eq(COLLECTION))
            val path = context.getBean(MongoDBAtlasVectorStoreProperties::class.java).pathName
            val index = ClassPathResource("atlas-index.json").inputStream.use { mapper.readTree(it) }
            val vectorField = index["fields"].single { it["type"].stringValue() == "vector" }
            assertEquals(path, vectorField["path"].stringValue())
            assertEquals(768, vectorField["numDimensions"].asInt())
            val converter = MappingMongoConverter(NoOpDbRefResolver.INSTANCE, MongoMappingContext())
            converter.afterPropertiesSet()
            saved.allValues.forEachIndexed { index, value ->
                assertEquals(originals[index].text, ReflectionTestUtils.getField(value, "content"))
                assertEquals(originals[index].metadata, ReflectionTestUtils.getField(value, "metadata"))
                val document = org.bson.Document()
                converter.write(value, document)
                assertEquals(List(768) { if (it == index) 1.0 else 0.0 },
                    document.getList(path, Number::class.java)?.map { it.toDouble() })
            }
            assertFalse(requests.any { it.body.contains("batch-not-for-embedding") })
        }
    }

    @Test
    fun `vector search embeds the query with its task and returns unmodified source text`() = withServer { client, requests ->
        val mongo = Mockito.mock(MongoTemplate::class.java)
        val source = "로그인 인증을 Spring Boot로 개편했습니다."
        Mockito.`when`(mongo.aggregate(Mockito.any(Aggregation::class.java), Mockito.anyString(), Mockito.eq(org.bson.Document::class.java)))
            .thenReturn(AggregationResults(listOf(org.bson.Document(mapOf(
                "_id" to "project_login", "content" to source,
                "metadata" to org.bson.Document("chunk_id", "project_login"), "score" to 0.9
            ))), org.bson.Document()))
        contextRunner(client, mongo).run { context ->
            assertNull(context.startupFailure)
            val results = context.getBean(MongoDBAtlasVectorStore::class.java)
                .similaritySearch(SearchRequest.builder().query("인증 시스템을 개선한 경험은?").topK(3).build())

            assertEquals(listOf("task: search result | query: 인증 시스템을 개선한 경험은?"), embeddedTexts(requests))
            assertRequestContract(requests)
            assertEquals(source, results.single().text)
            assertEquals("project_login", results.single().metadata["chunk_id"])
            Mockito.verify(mongo).aggregate(Mockito.any(Aggregation::class.java), Mockito.eq(COLLECTION), Mockito.eq(org.bson.Document::class.java))
        }
    }

    private fun contextRunner(client: Client, mongo: MongoTemplate): ApplicationContextRunner {
        val connection = Mockito.mock(GoogleGenAiEmbeddingConnectionDetails::class.java)
        Mockito.`when`(connection.genAiClient).thenReturn(client)
        Mockito.`when`(connection.getModelEndpointName(Mockito.anyString())).thenAnswer { it.arguments[0] }
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GoogleGenAiTextEmbeddingAutoConfiguration::class.java))
            .withUserConfiguration(MongoVectorStoreConfig::class.java)
            .withBean(GoogleGenAiEmbeddingConnectionDetails::class.java, Supplier { connection })
            .withBean(MongoTemplate::class.java, Supplier { mongo })
            .withInitializer { context ->
                YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                    .forEach { context.environment.propertySources.addLast(it) }
            }
            .withPropertyValues("GEMINI_API_KEY=local-embedding-test-only")
    }

    private fun entries(requests: List<Request>): List<JsonNode> = requests.flatMap {
        mapper.readTree(it.body)["requests"].toList()
    }

    private fun embeddedTexts(requests: List<Request>) = entries(requests).map {
        it["content"]["parts"].single()["text"].stringValue()
    }

    private fun assertRequestContract(requests: List<Request>) {
        requests.forEach {
            assertEquals("/v1beta/models/gemini-embedding-2:batchEmbedContents", it.path)
            assertEquals("local-embedding-test-only", it.apiKey)
        }
        entries(requests).forEach {
            assertEquals("models/gemini-embedding-2", it["model"].stringValue())
            assertEquals(768, it["outputDimensionality"].asInt())
            assertFalse(it.has("taskType"))
            assertFalse(it.has("task_type"))
        }
    }

    private fun withServer(action: (Client, List<Request>) -> Unit) {
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += Request(exchange.requestURI.path, exchange.requestHeaders.getFirst("x-goog-api-key"), body)
            val count = mapper.readTree(body)["requests"].size()
            val bytes = mapper.writeValueAsBytes(mapOf("embeddings" to (0 until count).map { index ->
                mapOf("values" to List(768) { if (it == index) 1.0 else 0.0 })
            }))
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val client = Client.builder().apiKey("local-embedding-test-only")
            .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:${server.address.port}").apiVersion("v1beta").timeout(5000).build())
            .build()
        try {
            action(client, requests)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    private data class Request(val path: String, val apiKey: String?, val body: String)

    companion object {
        private const val COLLECTION = "resume_chunks_embedding2"
    }
}
