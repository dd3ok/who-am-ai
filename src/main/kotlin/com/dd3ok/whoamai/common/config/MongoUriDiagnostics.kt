package com.dd3ok.whoamai.common.config

import com.mongodb.ConnectionString
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class MongoUriDiagnostics(
    @Value("\${spring.data.mongodb.uri:}") private val springMongoUri: String
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val envMongoUri = System.getenv("MONGO_URI").orEmpty()
        val envTarget = MongoUriLogSanitizer.describe(envMongoUri)
        val springTarget = MongoUriLogSanitizer.describe(springMongoUri)

        logger.info(
            "MongoDB URI diagnostics: MONGO_URI present={}, MONGO_URI target={}, spring.data.mongodb.uri present={}, spring.data.mongodb.uri target={}",
            envMongoUri.isNotBlank(),
            envTarget,
            springMongoUri.isNotBlank(),
            springTarget
        )

        if (springMongoUri.isBlank()) {
            logger.warn("spring.data.mongodb.uri is empty. MongoDB will not use the expected Atlas URI.")
        } else if (MongoUriLogSanitizer.isLocalhost(springMongoUri)) {
            logger.warn("spring.data.mongodb.uri resolves to localhost. Check Render environment variable injection.")
        }
    }
}

object MongoUriLogSanitizer {
    fun describe(uri: String): String {
        if (uri.isBlank()) {
            return "empty"
        }

        return try {
            val connectionString = ConnectionString(uri)
            val hosts = connectionString.hosts.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "unknown"
            val database = connectionString.database?.takeIf { it.isNotBlank() } ?: "<none>"
            "hosts=$hosts,database=$database"
        } catch (_: IllegalArgumentException) {
            "invalid"
        }
    }

    fun isLocalhost(uri: String): Boolean {
        if (uri.isBlank()) {
            return false
        }

        return try {
            ConnectionString(uri).hosts.any { host ->
                val normalized = host.substringBefore(':').trim('[', ']').lowercase()
                normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
