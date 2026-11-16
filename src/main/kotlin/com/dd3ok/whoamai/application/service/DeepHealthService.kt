package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.out.ResumeProviderPort
import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

interface DeepHealthService {
    suspend fun check(): DeepHealthReport
}

data class DeepHealthReport(
    val status: String,
    val components: Map<String, DeepHealthComponent>
)

data class DeepHealthComponent(
    val status: String,
    val detail: String
)

interface DeepHealthMongoProbe {
    suspend fun ping(): Boolean
    suspend fun collectionExists(collectionName: String): Boolean
}

@Service
class DefaultDeepHealthService(
    private val resumeProviderPort: ResumeProviderPort,
    private val mongoProbeProvider: ObjectProvider<DeepHealthMongoProbe>,
    private val vectorStorePropertiesProvider: ObjectProvider<MongoDBAtlasVectorStoreProperties>,
    @Value("\${spring.mongodb.uri:}") private val mongoUri: String,
    @Value("\${spring.ai.openai.api-key:}") private val chatApiKey: String,
    @Value("\${spring.ai.google.genai.embedding.api-key:}") private val embeddingApiKey: String
) : DeepHealthService {

    override suspend fun check(): DeepHealthReport {
        val mongo = mongoStatus()
        val vector = vectorStatus(mongo.status == UP)
        val resume = resumeStatus()
        val chat = configuredStatus(chatApiKey, "configured", "api key missing")
        val embedding = configuredStatus(embeddingApiKey, "configured", "api key missing")

        val components = mapOf(
            "mongo" to mongo,
            "vector" to vector,
            "resume" to resume,
            "chat_model" to chat,
            "embedding" to embedding
        )
        val status = if (components.values.any { it.status == DOWN }) DOWN else UP
        return DeepHealthReport(status = status, components = components)
    }

    private suspend fun mongoStatus(): DeepHealthComponent {
        if (mongoUri.isBlank()) {
            return DeepHealthComponent(DOWN, "mongo uri is not configured")
        }
        val probe = mongoProbeProvider.ifAvailable
            ?: return DeepHealthComponent(DOWN, "mongo probe is not available")
        return try {
            if (probe.ping()) {
                DeepHealthComponent(UP, "reachable")
            } else {
                DeepHealthComponent(DOWN, "ping failed")
            }
        } catch (e: Exception) {
            DeepHealthComponent(DOWN, e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun vectorStatus(mongoUp: Boolean): DeepHealthComponent {
        if (!mongoUp) {
            return DeepHealthComponent(DOWN, "mongo is unavailable")
        }
        val probe = mongoProbeProvider.ifAvailable
            ?: return DeepHealthComponent(DOWN, "mongo probe is not available")
        val collectionName = vectorStorePropertiesProvider.ifAvailable
            ?.collectionName
            ?.takeIf { it.isNotBlank() }
            ?: "resume_chunks_embedding2"
        return try {
            if (probe.collectionExists(collectionName)) {
                DeepHealthComponent(UP, "collection '$collectionName' exists")
            } else {
                DeepHealthComponent(DOWN, "collection '$collectionName' is missing")
            }
        } catch (e: Exception) {
            DeepHealthComponent(DOWN, e.message ?: e::class.java.simpleName)
        }
    }

    private fun resumeStatus(): DeepHealthComponent {
        return try {
            if (resumeProviderPort.isInitialized()) {
                DeepHealthComponent(UP, "resume loaded")
            } else {
                DeepHealthComponent(DOWN, "resume is not initialized")
            }
        } catch (e: Exception) {
            DeepHealthComponent(DOWN, e.message ?: e::class.java.simpleName)
        }
    }

    private fun configuredStatus(value: String, upDetail: String, downDetail: String): DeepHealthComponent {
        return if (value.isNotBlank()) {
            DeepHealthComponent(UP, upDetail)
        } else {
            DeepHealthComponent(DOWN, downDetail)
        }
    }

    private companion object {
        const val UP = "UP"
        const val DOWN = "DOWN"
    }
}
