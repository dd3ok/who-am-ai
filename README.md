# Who Am AI

이력서 JSON을 기반으로 답변하는 AI 이력서 RAG 챗봇입니다. Kotlin과 Spring Boot 4로 작성되어 있고, 이력서 내용을 청크로 나눈 뒤 MongoDB Atlas Vector Search에 임베딩을 저장합니다. 사용자는 WebSocket으로 질문을 보내고, 서비스는 관련 이력 컨텍스트를 찾아 Gemini 모델로 답변을 스트리밍합니다.

## 주요 기능

- 이력서 JSON 청크 분할과 임베딩 저장
- 규칙 기반 검색과 벡터 검색을 함께 쓰는 RAG 흐름
- WebSocket 기반 스트리밍 답변
- Spring AI 2.0 Google GenAI 연동
- Gemini chat 모델 fallback 설정
- MongoDB Atlas Vector Search 기반 이력 컨텍스트 조회
- 관리자용 이력서 재색인 API
- 헬스체크와 Actuator health endpoint

## 기술 스택

- 언어 및 런타임: Kotlin 2.3.21, JDK 21
- 프레임워크: Spring Boot 4.1.0, Spring WebFlux, WebSocket
- AI: Spring AI 2.0.0, Google GenAI
- JSON: Jackson 3
- 기본 chat 모델: `gemini-3.1-flash-lite`
- fallback chat 모델: `gemini-2.5-flash-lite`, `gemini-3.5-flash`, `gemini-2.5-flash`
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
- Google GenAI API key
- Gradle Wrapper 실행이 가능한 셸 환경

## 환경 변수

실행에 필요한 기본 환경 변수입니다.

```bash
export MONGO_URI="mongodb+srv://<user>:<password>@<cluster>/?retryWrites=true&w=majority"
export GEMINI_API_KEY="<google-genai-key>"
```

관리자 API를 사용하려면 다음 값도 설정합니다.

```bash
export ADMIN_API_KEY="<admin-secret>"
```

## 설정

Spring AI 2.0에서는 Google GenAI 모델 옵션을 예전처럼 `.options` 하위에 두지 않습니다. 아래처럼 평탄화된 설정을 사용합니다.

```yaml
spring:
  ai:
    google:
      genai:
        chat:
          model: gemini-3.1-flash-lite
          temperature: 0.75
          max-output-tokens: 4096
        embedding:
          text:
            model: gemini-embedding-001
            dimensions: 768
            task-type: RETRIEVAL_DOCUMENT
```

답변 생성용 chat fallback 목록은 Spring AI 표준 설정이 아니라 애플리케이션 전용 설정인 `who-am-ai.ai.chat.*`에 둡니다. `spring.ai.google.genai.chat.model`은 Spring AI 자동 설정의 기본값으로 남겨 두고, 가독성을 위해 fallback 목록의 첫 번째 모델과 맞춥니다.

```yaml
who-am-ai:
  ai:
    chat:
      models: [gemini-3.1-flash-lite, gemini-2.5-flash-lite, gemini-3.5-flash, gemini-2.5-flash]
      temperature: 0.75
      max-output-tokens: 4096
      rate-limit-cooldown-ms: 60000
```

배포 환경에서 `application.yml`을 수정하지 않고 fallback 정책을 바꾸려면 Spring Boot 설정 방식으로 주입하면 됩니다.

```bash
export SPRING_APPLICATION_JSON='{"who-am-ai":{"ai":{"chat":{"models":["gemini-3.1-flash-lite","gemini-2.5-flash-lite","gemini-3.5-flash","gemini-2.5-flash"],"temperature":0.75,"max-output-tokens":4096,"rate-limit-cooldown-ms":60000}}}}'
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

WebSocket 메시지 예시는 다음과 같습니다.

```json
{"uuid":"demo","type":"USER","content":"Tell me about your experience."}
```

## Docker

Docker 이미지는 저장소에 포함된 Gradle Wrapper로 빌드하며, Spring Boot archive 이름은 `app.jar`로 고정되어 있습니다.

```bash
docker build -t who-am-ai .
docker run --rm -p 8080:8080 \
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
```

RAG benchmark prompt는 `src/test/resources/evals/` 아래에 있습니다. 실제 `MONGO_URI`와 `GEMINI_API_KEY`가 필요한 benchmark 성격의 테스트를 돌릴 때는 비용과 quota를 고려해 prompt 수를 낮게 유지하는 편이 좋습니다.

```bash
BENCHMARK_MAX_PROMPTS=5 ./scripts/run-threshold-benchmark.sh
```

지원하는 benchmark 설정은 다음과 같습니다.

- `BENCHMARK_MAX_PROMPTS`: threshold별 최대 prompt 수
- `BENCHMARK_EXTRA_ARGS`: benchmark script에 넘길 추가 Gradle 인자
- `-Dbenchmark.max-prompts=<n>`: Gradle 직접 실행 시 최대 prompt 수
- `-Dbenchmark.prompt-timeout-ms=<ms>`: 답변 생성 benchmark timeout
- `-Drag.search.similarity-threshold=<value>`: 검색 유사도 threshold override

테스트 fixture 위치입니다.

- Router 평가 케이스: `src/test/resources/evals/router-eval-cases.json`
- RAG 검색 prompt: `src/test/resources/evals/rag-benchmark-prompts.txt`
- 이력서 QA prompt: `src/test/resources/evals/resume-qa-prompts.txt`

## Spring AI 2.0 호환성 메모

- 이 프로젝트는 Spring AI 2.0.0, Spring Boot 4.1.0, Spring Framework 7, Jackson 3을 기준으로 합니다.
- Spring AI Google GenAI chat과 embedding 옵션은 `spring.ai.google.genai.*.options.*` 경로를 쓰지 않습니다.
- Boot 4의 Jackson 3 런타임 API는 `tools.jackson.*` 아래에 있습니다. Jackson annotation은 런타임 classpath의 annotation artifact에 맞춰 `com.fasterxml.jackson.annotation`을 유지합니다.
- MongoDB Atlas VectorStore API는 현재 `add`와 `similaritySearch(SearchRequest.builder())` 사용 방식과 호환됩니다.
- embedding 모델이나 차원을 바꾸면 Atlas vector index와 저장된 이력서 청크를 함께 다시 맞춰야 합니다.

## Gemini 모델과 quota 메모

기본 chat 모델 순서는 quota가 가장 넉넉한 모델을 먼저 쓰고, 안정적인 fallback을 뒤에 둡니다. 한 모델에서 `429`, `quota`, `rate`, `exhausted` 계열 오류가 나면 `rate-limit-cooldown-ms` 동안 그 모델을 건너뛰어 같은 quota 오류를 반복하지 않습니다. 응답 토큰 기본값은 TPM 소모를 줄이기 위해 4096으로 둡니다.

- `gemini-3.1-flash-lite`: 기본 모델입니다. 현재 quota 기준으로 RPM과 RPD가 가장 넉넉해 일반 질의에 우선 사용합니다.
- `gemini-2.5-flash-lite`: 저비용 fallback입니다. 기본 모델이 일시적으로 막혔을 때 먼저 시도합니다.
- `gemini-3.5-flash`: 품질을 보강하는 stable fallback입니다. quota가 낮으므로 앞 모델이 실패했을 때만 사용합니다.
- `gemini-2.5-flash`: stable fallback입니다. 다른 stable 모델까지 제한될 때 마지막으로 사용합니다.
- `gemini-3-flash-preview`: AI Studio quota가 있다면 직접 fallback 목록 끝에 추가할 수 있습니다. preview 모델이라 기본값에는 넣지 않습니다.

Gemini API rate limit은 API key 단위가 아니라 Google Cloud project 단위로 적용됩니다. 일일 quota는 Pacific time 기준 자정에 초기화됩니다. RPM, TPM, RPD 값은 모델과 프로젝트, 사용 등급에 따라 달라질 수 있으므로 Google AI Studio에서 현재 값을 확인해야 합니다.

<https://aistudio.google.com/app/usage>
