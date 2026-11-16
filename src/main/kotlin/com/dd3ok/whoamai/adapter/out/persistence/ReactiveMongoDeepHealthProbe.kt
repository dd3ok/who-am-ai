package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.service.DeepHealthMongoProbe
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class ReactiveMongoDeepHealthProbe(
    private val mongoOperations: ReactiveMongoOperations
) : DeepHealthMongoProbe {

    override suspend fun ping(): Boolean {
        mongoOperations.collectionExists(CHAT_HISTORY_COLLECTION)
            .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
            .awaitSingle()
        return true
    }

    override suspend fun collectionExists(collectionName: String): Boolean {
        return mongoOperations.collectionExists(collectionName)
            .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
            .awaitSingle()
    }

    private companion object {
        const val CHAT_HISTORY_COLLECTION = "chat_histories"
        const val PROBE_TIMEOUT_MS = 1000L
    }
}
