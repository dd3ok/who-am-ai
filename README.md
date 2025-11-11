# Who Am AI — AI 이력서 챗봇

이력서 JSON을 벡터화해 MongoDB Atlas에 저장하고, WebSocket 기반으로 자연어 Q&A를 제공하는 Kotlin/Spring Boot 3 애플리케이션입니다. 기본 RAG를 표준 경로로 삼고, 예외 키워드 기반 Intent Decider로 일반 대화를 분기해 일관된 답변을 지향합니다.

## 한눈에 보기
- **스택:** Kotlin 1.9 · Spring Boot 3 · Spring AI (Google Gemini) · MongoDB Atlas Vector Search · WebFlux/WebSocket.
- **데이터:** `resume.json` → 청크/임베딩 → `resume_chunks` 컬렉션, 인덱스 정의는 `src/main/resources/atlas-index.json`.
- **인터페이스:** WebSocket `/ws/chat`, Admin POST `/api/admin/resume/reindex`, AI Fitting POST `/api/ai-fitting`.
- **환경 변수:** `MONGO_URI`, `GEMINI_API_KEY` (모두 `application.yml`에서 주입).
- **프로세스 문서화:** 모든 작업 기록은 `/docs` (plan/impl/review) 하위에 `YYMMDD-hhmmss-제목.md` 규칙으로 작성합니다. [see: Principles › 파일명 규칙](AGENTS.md#1-principles)

## 핵심 기능
- **기본 RAG + 예외 분기:** `QueryIntentDecider`가 일반 대화 키워드를 감지하면 대화 프롬프트로 우회하고, 나머지는 `ContextRetriever` 규칙/벡터 검색을 거쳐 RAG 프롬프트로 처리합니다.
- **RAG + 스트리밍:** 규칙/Intent 힌트로 Mongo Atlas 벡터 검색을 수행하고, `ChatService`가 Gemini 응답 토큰을 WebSocket으로 스트리밍합니다.
- **대화 상태 유지:** `ChatHistoryRepository`가 세션별 메시지를 저장하여 후속 질문에서도 맥락을 재사용합니다.
- **재색인 & 데이터 관리:** `/api/admin/resume/reindex`가 `resume.json`을 다시 청킹/임베딩해 Atlas에 반영합니다.
- **이미지 AI Fitting:** `/api/ai-fitting`이 인물·의상 이미지를 받아 Gemini Vision API로 스타일 합성 이미지를 생성합니다.
- **안전장치:** AI Fitting은 IP당 `rate-limit.requests`(기본 10) / `rate-limit.minutes`(기본 60) 정책을 적용하며 `?limit=no`로 우회 가능, 전역 CORS/WebSocket 설정 및 헬스체크(`/actuator/health` 대체) 제공.

## 시스템 흐름
### 1. 학습(Ingestion)
1. `ResumeChunkingService`가 `resume.json`을 요약/경력/프로젝트 단위로 자릅니다.
2. `ResumeDataProvider` → Gemini 임베딩(`text-embedding-004`) 생성.
3. 결과를 `MongoVectorAdapter`가 `resume_chunks` 컬렉션에 저장(필터 필드: `chunk_type`, `company`, `skills`).

### 2. 대화(Generation)
1. WebSocket `/ws/chat`으로 `{ "uuid": "...", "type": "USER", "content": "경력 알려줘" }` 형태 메시지 수신.
2. `QueryIntentDecider`가 일반 대화 예외 키워드를 검사 → 해당 시 바로 대화 프롬프트 사용.
3. 기본 경로에서는 `ContextRetriever` 규칙 매칭 → 실패 시 벡터 검색(`MongoVectorAdapter`)으로 컨텍스트 확보.
4. `ChatService`가 프롬프트(`prompts.rag-template` 또는 `conversational-template`)를 구성해 Gemini 채팅 모델(`gemini-2.5-flash-lite`) 호출.
5. 생성 토큰을 `StreamChatWebSocketHandler`가 실시간으로 사용자에게 전송.

## 구성요소 & 엔드포인트
- **WebSocket `/ws/chat`**: JSON 기반 단일 채널, 서버는 순차적으로 사용자 메시지를 읽고 `StreamMessage` 타입으로 응답합니다.
- **POST `/api/admin/resume/reindex`**: resume 데이터를 다시 읽고 Mongo Atlas에 반영. 성공 문자열에 `finished` 포함 시 200 OK.
- **POST `/api/ai-fitting` (multipart)**: `personImage`, `clothingImage` 필드 필요. Rate limit 초과 시 429, `?limit=no`로 우회 가능(운영 환경에서만 사용 권장).
- **HealthcheckController**: `/api/health` (간단 OK 문자열) 제공.

## 로컬 실행 절차
1. **필수 준비물**: JDK 21+, Docker 미필수, MongoDB Atlas 프로젝트, Google AI Studio 키.
2. **클론**
   ```bash
   git clone <repo>
   cd who-am-ai
   ```
3. **환경 변수 설정**
   ```bash
   export MONGO_URI="mongodb+srv://<user>:<pw>@cluster/?retryWrites=true&w=majority"
   export GEMINI_API_KEY="your-google-genai-key"
   ```
4. **Atlas 인덱스 생성**: `resume_chunks` 컬렉션 생성 후 `src/main/resources/atlas-index.json` 내용을 Search Index(JSON Editor)에 붙여 넣어 `vector_index` 생성.
5. **이력서 데이터 편집**: `src/main/resources/resume.json`을 개인 정보로 갱신.
6. **애플리케이션 실행**
   ```bash
   ./gradlew bootRun
   ```
7. **기본 확인**
   - WebSocket: `ws://localhost:8080/ws/chat` 연결 후 `{"uuid":"demo","type":"USER","content":"학력 알려줘"}` 전송.
   - Reindex: `POST http://localhost:8080/api/admin/resume/reindex`.
   - AI Fitting: `POST http://localhost:8080/api/ai-fitting` (multipart, 이미지 2개).

## 운영 & 개발 메모
- 로그 레벨: `com.dd3ok.whoamai=DEBUG`, 그 외 WARN.
- Rate limit 조정은 `application.yml`의 `rate-limit.*` 값 변경으로 처리.
- 프롬프트는 `src/main/resources/prompts/*.st`에 저장하고 `application.yml`에서는 경로만 지정합니다.
- 문서·구현 절차: Plan → (승인) → Implement 순으로 진행하고 Drift/테스트 시나리오는 `/docs/impl` 문서에 기록합니다.
- 테스트는 실제 실행 대신 시나리오를 문서화하고, 필요 시 사람만 로컬 스모크 테스트를 수행합니다.

## 향후 개선 아이디어
- 하이브리드 검색(벡터 + 키워드) 도입으로 특정 키워드 질의 정밀도 개선.
- Rerank 또는 Score Fusion 추가로 LLM에 전달할 컨텍스트 품질 향상.
- 응답 평가 파이프라인(Eval harness) 도입으로 변경 영향 추적.
- Agentic RAG 시나리오 도입해 부족한 답을 보강하는 후속 질의 자동화.
