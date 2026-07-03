package com.dd3ok.whoamai.common.config

import com.mongodb.ConnectionString
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class MongoUriDiagnostics(
    @Value("\${spring.mongodb.uri:}") private val bootMongoUri: String,
    @Value("\${spring.data.mongodb.uri:}") private val dataMongoUri: String
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val envMongoUri = System.getenv("MONGO_URI").orEmpty()
        val envTarget = MongoUriLogSanitizer.describe(envMongoUri)
        val bootTarget = MongoUriLogSanitizer.describe(bootMongoUri)
        val dataTarget = MongoUriLogSanitizer.describe(dataMongoUri)

        logger.info(
            "MongoDB URI diagnostics: MONGO_URI present={}, MONGO_URI target={}, spring.mongodb.uri present={}, spring.mongodb.uri target={}, spring.data.mongodb.uri present={}, spring.data.mongodb.uri target={}",
            envMongoUri.isNotBlank(),
            envTarget,
            bootMongoUri.isNotBlank(),
            bootTarget,
            dataMongoUri.isNotBlank(),
            dataTarget
        )

        if (bootMongoUri.isBlank()) {
            logger.warn("spring.mongodb.uri is empty. Reactive MongoDB will not use the expected Atlas URI.")
        } else if (MongoUriLogSanitizer.isLocalhost(bootMongoUri)) {
            logger.warn("spring.mongodb.uri resolves to localhost. Check Render environment variable injection.")
        }

        if (dataMongoUri.isBlank()) {
            logger.warn("spring.data.mongodb.uri is empty. Synchronous MongoTemplate will not use the expected Atlas URI.")
        } else if (MongoUriLogSanitizer.isLocalhost(dataMongoUri)) {
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
