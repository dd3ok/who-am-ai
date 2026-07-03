package com.dd3ok.whoamai

import org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration
import org.springframework.boot.runApplication

@ConfigurationPropertiesScan
@SpringBootApplication(
    exclude = [
        DataMongoAutoConfiguration::class,
        MongoDBAtlasVectorStoreAutoConfiguration::class
    ]
)
class WhoAmAiApplication

fun main(args: Array<String>) {
    runApplication<WhoAmAiApplication>(*args)
}
