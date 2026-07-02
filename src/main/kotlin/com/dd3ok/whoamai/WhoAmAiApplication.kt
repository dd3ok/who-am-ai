package com.dd3ok.whoamai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@ConfigurationPropertiesScan
@SpringBootApplication
class WhoAmAiApplication

fun main(args: Array<String>) {
    runApplication<WhoAmAiApplication>(*args)
}
