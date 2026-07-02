# Who Am AI - AI resume RAG chatbot

Kotlin/Spring Boot 4 service that chunks a resume JSON, stores embeddings in MongoDB Atlas Vector Search, and answers resume questions over WebSocket.

## Stack

- Runtime: Kotlin 2.3.21, Spring Boot 4.1.0, WebFlux/WebSocket, JDK 21
- AI: Spring AI 2.0.0 with Google GenAI
- JSON: Jackson 3 through Spring Boot's `spring-boot-starter-jackson`
- Chat models: `gemini-3.1-flash-lite`, then `gemini-3.5-flash`, then `gemini-2.5-flash-lite` fallback
- Embedding model: `gemini-embedding-001` with 768 dimensions
- Storage: MongoDB Atlas Vector Search, `resume_chunks` collection, `vector_index` index
- Build: checked-in Gradle wrapper

## Flow

1. `resume.json` is chunked by `ResumeChunkingService`.
2. Chunks are embedded and stored through `MongoVectorAdapter`.
3. `LLMRouter` uses heuristics to choose `RESUME_RAG` or `NON_RAG`.
4. `ContextRetriever` tries rule-based matches, then vector search.
5. `ChatService` renders `prompts/*.st` and streams the final answer.

## Endpoints

- WebSocket: `/ws/chat`
- Admin reindex: `POST /api/admin/resume/reindex`
- App healthcheck: `GET /api/healthcheck` returns a lightweight status response
- Actuator health: `GET /actuator/health`

Admin endpoints fail closed unless `ADMIN_API_KEY` is set. Call them with the matching API key header:

```bash
curl -X POST http://localhost:8080/api/admin/resume/reindex \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY"
```

## Configuration

Required:

```bash
export MONGO_URI="mongodb+srv://<user>:<password>@<cluster>/?retryWrites=true&w=majority"
export GEMINI_API_KEY="<google-genai-key>"
```

Admin key, required for `/api/admin/**`:

```bash
export ADMIN_API_KEY="<admin-secret>"
```

Spring AI 2.0 uses flattened model options. Do not put new settings under the old `.options` segment:

```yaml
spring:
  ai:
    google:
      genai:
        chat:
          model: gemini-3.1-flash-lite
          temperature: 0.75
          max-output-tokens: 8192
        embedding:
          text:
            model: gemini-embedding-001
            dimensions: 768
            task-type: RETRIEVAL_DOCUMENT
```

This app keeps its custom chat fallback list under its own namespace so it does not rely on Spring AI accepting non-standard Google GenAI properties. Runtime answer generation uses `who-am-ai.ai.chat.*`; `spring.ai.google.genai.chat.model` remains the Spring AI auto-configuration default and should match the first fallback model for clarity.

```yaml
who-am-ai:
  ai:
    chat:
      models: [gemini-3.1-flash-lite, gemini-3.5-flash, gemini-2.5-flash-lite]
      temperature: 0.75
      max-output-tokens: 8192
```

For deployments that need to override the fallback policy without editing `application.yml`, pass the app-owned settings through Spring Boot configuration. For example:

```bash
export SPRING_APPLICATION_JSON='{"who-am-ai":{"ai":{"chat":{"models":["gemini-3.1-flash-lite","gemini-3.5-flash"],"temperature":0.75,"max-output-tokens":8192}}}}'
```

## Spring AI 2 Compatibility Notes

- The project targets Spring AI 2.0.0, Spring Boot 4.1.0, Spring Framework 7, and Jackson 3.
- Spring AI Google GenAI chat and embedding options no longer use `spring.ai.google.genai.*.options.*`.
- Boot 4 uses Jackson 3 runtime APIs under `tools.jackson.*`. Jackson annotations remain under `com.fasterxml.jackson.annotation`, matching the annotation artifact on the runtime classpath.
- The MongoDB Atlas VectorStore API remains compatible with the existing `add` and `similaritySearch(SearchRequest.builder())` usage.
- `gemini-embedding-001` remains configured explicitly because the Atlas index is defined for 768-dimensional vectors. Changing embedding models or dimensions requires rebuilding the Atlas vector index and reindexing the resume chunks.

## Gemini Model And Quota Notes

The default chat model order favors cost-efficient Gemini models, but availability, pricing, and quota can change by model, project, and usage tier:

- `gemini-3.1-flash-lite`: cost-efficient Gemini 3.1 Flash-Lite model. Check live availability, pricing, and quota in Google AI Studio.
- `gemini-3.5-flash`: stronger fallback for broader Gemini 3.5 capacity.
- `gemini-2.5-flash-lite`: conservative fallback if a Gemini 3 endpoint is unavailable or rate-limited.

Google applies Gemini API rate limits per project, not per API key, and daily quotas reset at midnight Pacific time. Exact active RPM/TPM/RPD limits are not fixed in this repo because Google says they vary by model, project, and usage tier; check the live values in Google AI Studio: <https://aistudio.google.com/app/usage>.

Atlas setup:

- Create a Search index named `vector_index` on `resume_chunks`.
- Use `src/main/resources/atlas-index.json`.
- Vector path is `content_embedding`.

## Run

Use JDK 21 to launch Gradle. The project has a Gradle toolchain, but this Gradle/Kotlin setup is validated with `JAVA_HOME` pointing to JDK 21.

```bash
./gradlew bootRun
```

Smoke checks:

```bash
curl http://localhost:8080/api/healthcheck
curl http://localhost:8080/actuator/health
```

WebSocket message shape:

```json
{"uuid":"demo","type":"USER","content":"Tell me about your experience."}
```

## Docker

The Docker build uses the checked-in Gradle wrapper and packages the fixed `app.jar` boot archive.

```bash
docker build -t who-am-ai .
docker run --rm -p 8080:8080 \
  -e MONGO_URI="$MONGO_URI" \
  -e GEMINI_API_KEY="$GEMINI_API_KEY" \
  -e ADMIN_API_KEY="$ADMIN_API_KEY" \
  who-am-ai
```

## Tests And Benchmarks

```bash
./gradlew test
./gradlew test --tests "*LLMRouterEvalTest"
./gradlew test --tests "*ContextRetrieverTest"
```

RAG benchmark prompts live under `src/test/resources/evals/`. Live benchmark-style tests require real `MONGO_URI` and `GEMINI_API_KEY`; keep prompt counts low when tuning:

```bash
BENCHMARK_MAX_PROMPTS=5 ./scripts/run-threshold-benchmark.sh
```

Supported benchmark knobs:

- `BENCHMARK_MAX_PROMPTS`: max prompts per threshold for the script.
- `BENCHMARK_EXTRA_ARGS`: extra Gradle args for the script.
- `-Dbenchmark.max-prompts=<n>`: max prompts for direct Gradle runs.
- `-Dbenchmark.prompt-timeout-ms=<ms>`: timeout for answer-generation benchmark tests.
- `-Drag.search.similarity-threshold=<value>`: override retrieval threshold.

Useful test fixtures:

- Router cases: `src/test/resources/evals/router-eval-cases.json`
- RAG retrieval prompts: `src/test/resources/evals/rag-benchmark-prompts.txt`
- Resume QA prompts: `src/test/resources/evals/resume-qa-prompts.txt`
