package com.dd3ok.whoamai.common.config

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.model.StreamingChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.data.mongodb.core.MongoTemplate
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AiProviderConfigurationTest {
    @Test
    fun `OpenAI compatible chat and Gemini embeddings coexist when the lazy vector store is initialized`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                OpenAiChatAutoConfiguration::class.java,
                OpenAiEmbeddingAutoConfiguration::class.java,
                GoogleGenAiEmbeddingConnectionAutoConfiguration::class.java,
                GoogleGenAiTextEmbeddingAutoConfiguration::class.java
            ))
            .withUserConfiguration(MongoVectorStoreConfig::class.java)
            .withBean(MongoTemplate::class.java, Supplier { mock(MongoTemplate::class.java) })
            .withBean(ToolCallingManager::class.java, Supplier { ToolCallingManager.builder().build() })
            .withInitializer { context ->
                YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                    .forEach { context.environment.propertySources.addLast(it) }
            }
            .withPropertyValues("GEMINI_API_KEY=local-test-only", "GEMINI_EMBEDDING_API_KEY=local-test-only")
            .run { context ->
                assertNull(context.startupFailure)
                assertIs<OpenAiChatModel>(context.getBean(StreamingChatModel::class.java))
                assertEquals(setOf("googleGenAiTextEmbedding"), context.getBeansOfType(EmbeddingModel::class.java).keys)
                assertIs<GoogleGenAiTextEmbeddingModel>(context.getBean(EmbeddingModel::class.java))
                // Health/startup alone does not instantiate this lazy bean; the first RAG request does.
                assertNotNull(context.getBean(MongoDBAtlasVectorStore::class.java))
            }
    }
}
