# Who Am AI - AI resume RAG chatbot

Kotlin/Spring Boot 3 service that chunks a resume JSON, stores embeddings in MongoDB Atlas Vector Search, and answers resume questions over WebSocket.

## Stack

- Runtime: Kotlin 2.3.21, Spring Boot 3.5.14, WebFlux/WebSocket, JDK 21
- AI: Spring AI 1.1.6 with Google GenAI
- Chat models: `gemini-3.1-flash-lite`, then `gemini-3-flash-preview`, then `gemini-2.5-flash-lite` fallback
- Embedding model: `gemini-embedding-001` with 768 dimensions
- AI fitting image model: `gemini-2.5-flash-image`
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
- AI fitting: `POST /api/ai-fitting` with `personImage` and `clothingImage`
- App healthcheck: `GET /api/healthcheck` returns the chat history count
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

## Gemini Free Tier Notes

The default chat model order favors free-tier friendly Gemini 3 models:

- `gemini-3.1-flash-lite`: stable, cost-efficient Gemini 3.1 model with free input and output tokens.
- `gemini-3-flash-preview`: stronger Gemini 3 fallback with free standard input and output tokens, but preview limits can be more restrictive.
- `gemini-2.5-flash-lite`: conservative fallback if a Gemini 3 preview endpoint is unavailable or rate-limited.

Google applies Gemini API rate limits per project, not per API key, and daily quotas reset at midnight Pacific time. Exact active RPM/TPM/RPD limits are not fixed in this repo because Google says they vary by model, project, and usage tier; check the live values in Google AI Studio: <https://aistudio.google.com/app/usage>.

The image fitting model stays on `gemini-2.5-flash-image`. Current Gemini 3 image preview models such as `gemini-3.1-flash-image-preview` and `gemini-3-pro-image-preview` are not free-tier defaults, so they are intentionally not enabled here.

Atlas setup:

- Create a Search index named `vector_index` on `resume_chunks`.
- Use `src/main/resources/atlas-index.json`.
- Vector path is `content_embedding`.

## Run

Use JDK 21. The project has a Gradle toolchain, but `./gradlew` still needs a JDK 21-capable launcher on `PATH` or in `JAVA_HOME`.

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
{"uuid":"demo","type":"USER","content":"경력 알려줘"}
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
