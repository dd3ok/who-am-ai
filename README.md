# Who Am AI

이력서 JSON을 기반으로 답변하는 AI 이력서 RAG 챗봇입니다. Kotlin과 Spring Boot 4로 작성되어 있고, 이력서 내용을 청크로 나눈 뒤 MongoDB Atlas Vector Search에 임베딩을 저장합니다. 사용자는 WebSocket으로 질문을 보내고, 서비스는 관련 이력 컨텍스트를 찾아 Google Gemini 3.8 Flash로 답변을 스트리밍합니다. 채팅은 Google 공식 OpenAI 호환 엔드포인트, 임베딩은 Google GenAI API를 사용합니다.

## 주요 기능

- 이력서 JSON 청크 분할과 임베딩 저장
- 규칙 기반 검색과 벡터 검색을 함께 쓰는 RAG 흐름
- WebSocket 기반 스트리밍 답변
- Spring AI 2.0 OpenAI 호환 채팅과 Google GenAI 임베딩 연동
- 모델 요청 제한과 스트리밍 오류 처리
- MongoDB Atlas Vector Search 기반 이력 컨텍스트 조회
- 관리자용 이력서 재색인 API
- 헬스체크와 Actuator health endpoint

## 기술 스택

- 언어 및 런타임: Kotlin 2.3.21, JDK 21
- 프레임워크: Spring Boot 4.1.0, Spring WebFlux, WebSocket
- AI: Spring AI 2.0.0, Google Gemini chat / embedding
- JSON: Jackson 3
- chat 모델: `gemini-3.8-flash`, reasoning effort `medium`
- embedding 모델: `gemini-embedding-001`, 768 dimensions
- 저장소: MongoDB Atlas Vector Search
- 빌드: Gradle Wrapper

## 동작 흐름

1. `resume.json`을 `ResumeChunkingService`가 이력 항목 단위로 나눕니다.
2. `MongoVectorAdapter`가 청크를 임베딩하고 MongoDB Atlas에 저장합니다.
3. `LLMRouter`가 사용자 질문을 보고 `RESUME_RAG` 또는 `NON_RAG`로 분류합니다.
4. `ContextRetriever`가 먼저 규칙 기반으로 컨텍스트를 찾고, 필요하면 벡터 검색을 사용합니다.
5. `CareerContextPlanner`와 `CareerPromptAssembler`가 프롬프트 입력을 구성합니다.
6. `ChatService`가 Gemini 응답을 WebSocket 스트림으로 전달하고 대화 기록을 저장합니다.

## API

| 구분 | 경로 | 설명 |
| --- | --- | --- |
| WebSocket | `/ws/chat` | 채팅 메시지를 받고 답변을 스트리밍합니다. |
| WebSocket events | `/ws/chat/events` | 참고 자료, 응답 토큰, 완료·오류 이벤트를 전달합니다. |
| 익명 세션 | `POST /api/chat/sessions` | 대화 ID와 탭 단위 세션 토큰을 발급합니다. |
| Admin | `POST /api/admin/resume/reindex` | 이력서 청크를 다시 만들고 재색인합니다. |
| Healthcheck | `GET /api/healthcheck` | 앱 상태를 가볍게 확인합니다. |
| Actuator | `GET /actuator/health` | Spring Actuator health endpoint입니다. |

관리자 API는 `ADMIN_API_KEY`가 설정된 경우에만 열립니다. 호출할 때는 같은 값을 `X-Admin-Api-Key` 헤더로 전달해야 합니다.

```bash
curl -X POST http://localhost:8080/api/admin/resume/reindex \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY"
```

## 사전 준비

로컬 실행 전에 다음 항목이 준비되어 있어야 합니다.

- JDK 21
- MongoDB Atlas cluster
- MongoDB Atlas Vector Search index
- Google Gemini API key
- Gradle Wrapper 실행이 가능한 셸 환경

## 환경 변수

실행에 필요한 기본 환경 변수입니다.

```bash
export MONGO_URI="mongodb+srv://<user>:<password>@<cluster>/<database>?retryWrites=true&w=majority"
export GEMINI_API_KEY="<google-genai-key>"
```

`GEMINI_API_KEY`는 채팅과 임베딩에 공통으로 사용합니다. 임베딩만 별도 프로젝트를 쓰려면 선택적으로 `GEMINI_EMBEDDING_API_KEY`를 지정합니다.
키는 백엔드 환경변수로만 설정하며, 코드·프런트 공개 환경변수·로그에 넣지 않습니다.
`MONGO_URI`는 벡터를 저장하고 검색하는 DB 연결입니다. 질문을 벡터로 만드는 임베딩 API를 대신하지 않습니다.
기존 `gemini-embedding-001`과 768차원 Atlas 인덱스를 유지합니다.

관리자 API를 사용하려면 다음 값도 설정합니다.

```bash
export ADMIN_API_KEY="<admin-secret>"
```

## 설정

채팅은 Google의 OpenAI 호환 API 형식을 사용하며, 실제 요청은 Google로 전송됩니다. Spring AI 2.0 모델 옵션은 `.options` 하위가 아닌 아래 경로에 둡니다.

```yaml
spring:
  ai:
    model:
      chat: openai
      embedding:
        text: google-genai
    openai:
      base-url: https://generativelanguage.googleapis.com/v1beta/openai
      api-key: ${GEMINI_API_KEY}
      timeout: 110s
      max-retries: 0
      chat:
        model: gemini-3.8-flash
        reasoning-effort: medium
        max-tokens: 8192
    google:
      genai:
        api-key: ${GEMINI_EMBEDDING_API_KEY:${GEMINI_API_KEY:}}
        embedding:
          api-key: ${spring.ai.google.genai.api-key}
          text:
            model: gemini-embedding-001
            dimensions: 768
            task-type: RETRIEVAL_DOCUMENT
```

요청별 모델 옵션은 기존 애플리케이션 설정인 `who-am-ai.ai.chat.*`에 둡니다. 모델과 추론 단계는 `spring.ai.openai.chat.*` 기본값과 맞춥니다.

```yaml
who-am-ai:
  ai:
    chat:
      models: [gemini-3.8-flash]
      reasoning-effort: medium
      max-output-tokens: 8192
      rate-limit-cooldown-ms: 60000
```

배포 환경에서 `application.yml`을 수정하지 않고 모델 옵션을 바꾸려면 Spring Boot 설정 방식으로 주입하면 됩니다.

```bash
export SPRING_APPLICATION_JSON='{"who-am-ai":{"ai":{"chat":{"models":["gemini-3.8-flash"],"reasoning-effort":"medium","max-output-tokens":8192,"rate-limit-cooldown-ms":60000}}}}'
```

## MongoDB Atlas Vector Search

Atlas에는 `resume_chunks` 컬렉션과 `vector_index` Search index가 필요합니다.

- 인덱스 이름: `vector_index`
- 컬렉션: `resume_chunks`
- 벡터 필드: `content_embedding`
- 인덱스 설정 파일: `src/main/resources/atlas-index.json`

`gemini-embedding-001`은 768차원 벡터로 명시되어 있습니다. embedding 모델이나 차원을 바꾸면 Atlas vector index를 다시 만들고 이력서 청크도 재색인해야 합니다.

## 실행

Gradle 실행에는 JDK 21을 사용합니다. 프로젝트에 Gradle toolchain 설정이 있지만, 현재 Gradle/Kotlin 조합은 `JAVA_HOME`이 JDK 21을 가리키는 환경에서 검증되어 있습니다.

```bash
./gradlew bootRun
```

기본 상태는 다음 명령으로 확인합니다.

```bash
curl http://localhost:8080/api/healthcheck
curl http://localhost:8080/actuator/health
```

Create a session from an allowed browser origin first:

```bash
curl -X POST http://localhost:8080/api/chat/sessions -H 'Origin: http://localhost:3000'
```

The `201` response contains `conversationId`, `sessionToken`, and `expiresAt` with `Cache-Control: no-store`.
Send the returned values in every WebSocket message; keep the token in tab-scoped storage and never put it in a URL or logs:

```json
{"uuid":"<conversationId>","sessionToken":"<sessionToken>","type":"USER","content":"경력 알려줘"}
```

Both WebSocket endpoints accept the same request. `/ws/chat` keeps its existing plain-text token responses.
`/ws/chat/events` returns JSON frames, grouped by a server-generated `requestId`:

```json
{"type":"SOURCES","sources":[{"id":"project_ESM_로그인_개편","title":"ESM 로그인 개편","url":null}],"requestId":"..."}
{"type":"DELTA","content":"로그인·인증 구조를 개편했습니다.","requestId":"..."}
{"type":"DONE","requestId":"..."}
```

`SOURCES` lists the documents supplied to the model; label these as **참고 자료**, not verified sentence-level citations.
Only render a source link when `url` is present. Source titles and optional HTTP(S) URLs come from registered resume data,
not generated model text. An `ERROR` event has `code` (`INVALID_MESSAGE`, `TIMEOUT`, or `TEMPORARY_ERROR`) and a public
`content` message. `SESSION_REQUIRED` means the token is missing, expired, or does not own the conversation;
`RATE_LIMITED` asks the client to wait one minute. A failed request has no `DONE`; partial text can precede an error.
`DONE` follows successful history storage. The portfolio frontend lives in [dd3ok.github.io](https://github.com/dd3ok/dd3ok.github.io).

The defaults limit a frame to 64 KiB, a question to 4,000 characters, a conversation ID to 128 characters,
and each request to 45 seconds including retrieval and streaming. Configure these under `websocket` in `application.yml`.
Both endpoints require a server-issued session. Client-selected UUIDs alone no longer grant access to history.
Existing unsigned clients must be updated together with the backend; old UUID-only histories are not automatically adopted.

Sessions last 24 hours by default. MongoDB stores SHA-256 token hashes, conversation ownership and expiry.
Minute buckets enforce global, per-IP and per-session limits across application instances. Defaults are 60 new sessions/minute
(5/IP), and 120 messages/minute (30/IP, 10/session), configured under `chat-access`.
Fixed minute windows can allow a burst across a minute boundary; these are request budgets, not a precise billing cap.
The application uses the socket peer IP and does not parse arbitrary forwarded headers. A reverse proxy may make users share
one IP budget; validate trusted forwarding and limits in the deployment environment before enabling public traffic.
Origin checks are not authentication for non-browser clients; anonymous issuance remains public and rate-limited.
The first chat access creates TTL indexes for `chat_sessions` and `chat_request_buckets`, requiring index creation privileges.
An unavailable database keeps chat access closed and index setup is retried on the next request; application health stays independent.
Expiry is checked on each message even before MongoDB's asynchronous TTL deletion runs.

History appends reuse the existing atomic `revision` check and repository retries (up to three attempts),
without repeating model generation. Existing records without a revision gain one on append. Concurrent completed turns are
stored in save order; answers already generating in another tab do not incorporate that unfinished turn.
The event endpoint reports a storage failure without `DONE`; the legacy endpoint retains best-effort history storage and its
JSON error payload format (`type`, `code`, `message`, `retryAfterMs`).

## Portfolio Data And Follow-up Questions

Project records may include `problem`, `contribution`, `outcome`, and `sourceUrl`; existing records without them remain valid.
The three expanded ESM examples reorganize existing recorded facts and explicitly mark unavailable numerical results.
Add only verified facts, and use `sourceUrl` for a specific public project page when one exists.
WATCHLIST.md, savepoint and lucid use their public README descriptions and repository links, reviewed on 2026-11-15.
Their unrecorded dates and quantitative impact are explicitly left unavailable. The service profile links to who-am-ai itself.
The owner confirmed Gmarket's end month as `2025-01`; the frontend working copy uses the same date.
Project descriptions use verified implementation details only; no unverified decision rationale or measured impact is added.
Total experience is calculated in code using inclusive start/end months, counting overlapping months once and excluding gaps.
Missing or invalid dates leave the total unavailable instead of asking the model to guess.

After changing the packaged `resume.json`, redeploy/restart and invoke the authenticated reindex endpoint to update stored chunks.
No automatic production reindex runs during build or tests.

Short follow-ups such as `그래서 성과는?` use recent user questions to resolve the project or company before retrieval.
New topics and unrelated conversation stop that inheritance. Compound questions can retrieve multiple sections.
Model-generated text is not used to establish a new topic. Profile capability questions default to portfolio answers;
general study advice and questions explicitly about another subject remain general conversation.

`whoamai.chat.first_token.duration` measures history load, retrieval, and model time up to the first answer token.
`whoamai.chat.total.duration` includes history persistence. Existing model-only timing metrics remain available.

## Docker

Docker 이미지는 저장소에 포함된 Gradle Wrapper로 빌드하며, Spring Boot archive 이름은 `app.jar`로 고정되어 있습니다.

```bash
docker build -t who-am-ai .
docker run --rm -p 8080:10000 \
  -e MONGO_URI="$MONGO_URI" \
  -e GEMINI_API_KEY="$GEMINI_API_KEY" \
  -e ADMIN_API_KEY="$ADMIN_API_KEY" \
  who-am-ai
```

## 테스트

전체 테스트는 다음 명령으로 실행합니다.

```bash
./gradlew test
```

주요 테스트를 따로 실행할 수도 있습니다.

```bash
./gradlew test --tests "*LLMRouterEvalTest"
./gradlew test --tests "*ContextRetrieverTest"
./gradlew test --tests "*PortfolioRetrievalEvalTest"
./gradlew test --tests "*OpenAiCompatibilityTest"
```

The default `test`/`build` excludes tests tagged `live` and `mongo`, even when production credentials exist in the environment.
`PortfolioRetrievalEvalTest` runs 65 scenarios over the real resume/chunking/routing/prompt code with in-memory storage
and a fake model. It checks selected sources, follow-up behavior, empty-context handling, and source propagation;
it does **not** measure live vector-search relevance or generated-answer accuracy.

Career intent is determined by topic and ownership, without requiring a particular question ending.
Follow-ups and quoted answers can retain the user's topic, but conversation history is never evidence of career facts.
If a career question retrieves no nonblank context, the service returns a fixed missing-evidence response without calling the model.

Local MongoDB integration checks need no Gemini credentials or Atlas search index:

```bash
TEST_MONGO_URI=mongodb://127.0.0.1:27017 ./gradlew mongoTest
```

`mongoTest` accepts only localhost/127.0.0.1, creates a unique `whoamai_test_*` database per test and drops only that database.
It checks competing appends, migration of history without a revision, atomic shared counters, TTL indexes and session persistence.
CI runs it against a MongoDB service container alongside the build and Docker packaging check.

`OpenAiCompatibilityTest` checks actual Spring AI HTTP requests, Gemini model / medium reasoning settings, authentication headers, SSE responses, and failed-auth retry behavior using a local server and fake keys only.

Live tests require `EVAL_MONGO_URI` pointing to a **dedicated evaluation database** and `GEMINI_API_KEY`.
An optional `GEMINI_EMBEDDING_API_KEY` overrides the key used for embeddings only.
They reindex that evaluation database and use paid/quota-limited API calls. Review emitted answers for factual accuracy;
request errors and blank responses fail the run. Keep prompt counts low:

```bash
BENCHMARK_MAX_PROMPTS=5 ./scripts/run-threshold-benchmark.sh
./gradlew liveEval --tests "*ResumeQaAnswerTest" -Dbenchmark.max-prompts=5
```

지원하는 benchmark 설정은 다음과 같습니다.

- `BENCHMARK_MAX_PROMPTS`: max prompts per threshold for the script.
- `BENCHMARK_EXTRA_ARGS`: extra Gradle args for the script.
- `-Dbenchmark.max-prompts=<n>`: max prompts for direct Gradle runs.
- `-Dbenchmark.prompt-timeout-ms=<ms>`: timeout for answer-generation benchmark tests.
- `-Drag.search.similarity-threshold-override=<value>`: force the actual vector-search threshold for every query profile during comparison (0–1). Omit to retain the production per-query thresholds.

Benchmark and `rag.search.*` JVM properties are forwarded to the test JVM. Live evaluations always execute afresh.

테스트 fixture 위치입니다.

- Portfolio retrieval contracts: `src/test/resources/evals/portfolio-retrieval-cases.json`
- Router 평가 케이스: `src/test/resources/evals/router-eval-cases.json`
- RAG 검색 prompt: `src/test/resources/evals/rag-benchmark-prompts.txt`
- 이력서 QA prompt: `src/test/resources/evals/resume-qa-prompts.txt`

## Spring AI 2.0 호환성 메모

- 이 프로젝트는 Spring AI 2.0.0, Spring Boot 4.1.0, Spring Framework 7, Jackson 3을 기준으로 합니다.
- Spring AI 2.0.0 OpenAI streaming에는 `OpenAiChatOptions`를 전달합니다. 범용 `ChatOptions`는 해당 버전에서 런타임 형 변환 오류를 일으킵니다.
- Google GenAI embedding 옵션은 `spring.ai.google.genai.embedding.text.*` 경로를 사용합니다.
- Boot 4의 Jackson 3 런타임 API는 `tools.jackson.*` 아래에 있습니다. Jackson annotation은 런타임 classpath의 annotation artifact에 맞춰 `com.fasterxml.jackson.annotation`을 유지합니다.
- MongoDB Atlas VectorStore API는 현재 `add`와 `similaritySearch(SearchRequest.builder())` 사용 방식과 호환됩니다.
- embedding 모델이나 차원을 바꾸면 Atlas vector index와 저장된 이력서 청크를 함께 다시 맞춰야 합니다.

## 모델과 요청 제한

채팅 모델은 `gemini-3.8-flash`, 추론 강도는 `medium`입니다. Google의 OpenAI 호환 API에서 `reasoning_effort: medium`은 Gemini의 `thinking_level: medium`에 대응합니다. 기존 temperature 옵션은 제거하고 추론 단계로 제어합니다. `429` 등 요청 제한 응답을 받으면 기존 `rate-limit-cooldown-ms` 동안 모델 호출을 쉬며, 이미 일부 답변을 전송한 요청은 다른 모델로 이어 붙이지 않습니다. 최대 출력 토큰 기본값은 8192입니다.

긴 답변 뒤에도 이전 사용자 질문이 남도록 최근 대화의 범위를 함께 늘렸습니다. 기존 글자 수 기반 추정으로 API 대화창은 65,536토큰, 저장 대화창은 131,072토큰 이내로 제한합니다. 이는 모델 토크나이저의 정확한 토큰 수가 아닌 보수적 추정값입니다.

SDK의 자동 HTTP 재시도는 끄고 요청 타임아웃은 110초로 설정합니다. 전체 WebSocket 요청 제한은 120초이며, 프런트는 서버의 완료·오류 이벤트를 받을 수 있도록 150초까지 기다립니다.
설정은 [Google OpenAI 호환 API 문서](https://ai.google.dev/gemini-api/docs/openai#thinking)와 [Gemini 3.8 Flash 모델 문서](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)에서 확인합니다.
모델별 quota와 프로젝트 결제 상태는 Google AI Studio에서 확인합니다.
