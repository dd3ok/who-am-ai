# Who Am AI — AI 이력서 RAG 챗봇

Kotlin/Spring Boot 3 기반으로 이력서 JSON을 청크→임베딩→MongoDB Atlas Vector Search에 올리고, WebSocket으로 질의에 답하는 개인화 챗봇입니다.

규칙 기반 검색, LLM 라우팅, RAG를 조합해 정확도가 높은 답을 반환합니다.

## 주요 스택
- 언어/런타임: Kotlin 1.9+, Spring Boot 3, WebFlux/WebSocket
- LLM: Spring AI + Google Gemini 2.5 (일반: flash → flash-preview fallback, 라우팅: flash-lite → flash-lite-preview fallback), 임베딩 text-embedding-004
- 스토리지: MongoDB Atlas Vector Search (`resume_chunks` 컬렉션)
- 설정/빌드: Gradle, application.yml, atlas-index.json

## 아키텍처 & 흐름
1) **Ingestion (재색인)**  
`resume.json` → `ResumeChunkingService`로 요약/경력/프로젝트 등 청크 생성 → 임베딩 → `MongoVectorAdapter`가 `resume_chunks`에 저장(`chunk_type`, `company`, `skills`, `indexedAt` 메타).

2) **Routing**  
`LLMRouter` 전처리: 정체성/스택 질문 하드블록 → NON_RAG. 슬롯(회사/프로젝트/스킬 등) 감지 시 RAG 후보, 없으면 NON_RAG. 라우팅 LLM은 `routing-models` 우선순위(flash-lite → flash-lite-preview 등)로 호출. fail-safe: 컨텍스트 비면 NON_RAG로 전환.

3) **Context Retrieval**  
`ContextRetriever` 규칙 매칭(이름/관심사/회사/프로젝트 alias 등) → 컨텍스트 반환. 실패 시 Vector 검색(topK=3 기본, 필터는 값 없으면 제거).

4) **Generation**  
`ChatService`가 프롬프트 템플릿(`prompts/*.st`)을 렌더링. RAG는 Markdown 불릿/헤딩으로 응답하도록 지시, 대화용은 페르소나/정체성/스택 고정 안내 포함. 일반 챗 LLM은 `models` 우선순위(flash → flash-preview 등)로 호출해 결과를 WebSocket으로 스트리밍.

5) **AI Fitting**  
`/api/ai-fitting`에서 인물·의상 이미지를 받아 Gemini Vision(REST)으로 합성 이미지 생성. Rate limit(`rate-limit.*`) 적용.

## 주요 구성요소
- `PromptTemplateService`/`PromptProvider`: `prompts/*.st` 로딩 및 플레이스홀더 치환
- `LLMRouter`: 정체성 하드블록 + 슬롯 감지 + 라우팅 프롬프트
- `ContextRetriever`: 규칙 기반 매칭 후 Vector 검색 (필터 fallback)
- `MongoVectorAdapter`: Atlas VectorStore index/search
- `GeminiAdapter`: Chat/Streaming 호출, ImageModel(REST) 래퍼
- `ResumeChunkingService`: resume.json → 청크 생성

## 엔드포인트
- WebSocket: `/ws/chat` (StreamMessage JSON)
- Admin: `POST /api/admin/resume/reindex` (재색인)
- AI Fitting: `POST /api/ai-fitting` (multipart: `personImage`, `clothingImage`)
- Health: `/api/health`

## 설정
- 환경변수: `MONGO_URI`, `GEMINI_API_KEY`
- Atlas 인덱스: `src/main/resources/atlas-index.json`을 Search Index(JSON Editor)에 붙여 `vector_index` 생성
- Rate limit: `application.yml`의 `rate-limit.requests`, `rate-limit.minutes`

## 실행 절차
```bash
git clone https://github.com/dd3ok/who-am-ai
cd who-am-ai
export MONGO_URI="mongodb+srv://<user>:<pw>@cluster/?retryWrites=true&w=majority"
export GEMINI_API_KEY="<google-genai-key>"
./gradlew bootRun
```
- 재색인: `curl -X POST http://localhost:8080/api/admin/resume/reindex`
- WebSocket 스모크: `ws://localhost:8080/ws/chat` → `{"uuid":"demo","type":"USER","content":"경력 알려줘"}`
- AI Fitting: `POST /api/ai-fitting` (multipart로 이미지 2개 업로드)

## 운영/테스트 메모
- 로그: 라우팅 intent/slots, 검색 topK/필터, 컨텍스트 개수를 DEBUG로 남기면 품질 점검 용이
- 컨텍스트 비면 즉시 NON_RAG fallback 진행
- reindex 후 `metadata.indexedAt` 확인으로 반영 여부 점검
- 정체성/스택 질문은 RAG 미호출, 고정 스택 안내(Repo 링크 포함) 후 도움 제안

## 향후 개선 아이디어
- MMR/다양성 검색 실험 및 topK 튜닝
- slot 감지/필터 적용 로그 + 스모크 자동화로 회귀 방지
- 하이브리드 검색(벡터+키워드) 또는 rerank 도입 시나리오 검토
