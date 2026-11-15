plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.dd3ok"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Spring AI
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai-embedding")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-mongodb-atlas")

    // Kotlin
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // DB
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property"
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Gradle's JVM properties are not automatically passed to the forked test JVM.
    systemProperties(System.getProperties().entries
        .filter { it.key.toString().startsWith("benchmark.") || it.key.toString().startsWith("rag.search.") }
        .associate { it.key.toString() to it.value })
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("live", "mongo") }
}

tasks.register<Test>("mongoTest") {
    description = "Tests atomic persistence against a local MongoDB server, without Gemini or Atlas."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("mongo") }
    outputs.upToDateWhen { false }
    doFirst { require(!System.getenv("TEST_MONGO_URI").isNullOrBlank()) { "Set TEST_MONGO_URI to a local MongoDB server." } }
}

tasks.register<Test>("liveEval") {
    description = "Runs opt-in Gemini / MongoDB evaluations against EVAL_MONGO_URI. Reindexes the evaluation database."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    outputs.upToDateWhen { false }
    doFirst {
        require(!System.getenv("EVAL_MONGO_URI").isNullOrBlank()) { "Set EVAL_MONGO_URI to a dedicated evaluation database." }
        require(!System.getenv("GEMINI_API_KEY").isNullOrBlank()) { "GEMINI_API_KEY is required for live evaluations." }
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
