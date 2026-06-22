# Spring AI Best Practice Review (2026-02-15)

## 목적

- `who-am-ai` 코드베이스를 Spring AI 공식 문서 기준으로 다시 검토한다.
- 직접 구현한 기능 중 Spring AI 내장 기능으로 대체 가능한 부분을 찾는다.
- 현재 의사결정 제약, 즉 요청당 LLM 1회 호출을 유지하면서 먼저 적용할 개선 방향을 정리한다.

## 현재 제약 및 운영 원칙

- 채팅 요청 1건당 LLM 호출은 최종 응답 생성 1회로 제한한다.
- 라우팅 단계에서는 LLM을 호출하지 않는다.
- 품질을 우선한다. 근거 없는 응답을 줄이고 RAG 정밀도를 높이는 쪽을 먼저 본다.

## 현재 구조 요약 (코드 기준)

- 라우팅: 휴리스틱 기반 분기 (`LLMRouter`)
  - `src/main/kotlin/com/dd3ok/whoamai/application/service/LLMRouter.kt`
- 검색: 규칙 기반 조회 + 벡터 검색 + 휴리스틱 리랭크 (`ContextRetriever`)
  - `src/main/kotlin/com/dd3ok/whoamai/application/service/ContextRetriever.kt`
- 생성: RAG/일반 프롬프트 선택 후 스트리밍 응답 (`ChatService`)
  - `src/main/kotlin/com/dd3ok/whoamai/application/service/ChatService.kt`
- 모델 호출 어댑터: `ChatModel`/`StreamingChatModel` 직접 사용 (`GeminiAdapter`)
  - `src/main/kotlin/com/dd3ok/whoamai/adapter/out/gemini/GeminiAdapter.kt`
- 벡터스토어: Mongo Atlas VectorStore 직접 조회 (`MongoVectorAdapter`)
  - `src/main/kotlin/com/dd3ok/whoamai/adapter/out/persistence/MongoVectorAdapter.kt`

## 추가 검증: 직접 구현 vs Spring AI 내장 기능

### 1. 프롬프트 렌더링

- 현재: `PromptTemplateService`에서 리소스를 읽고 문자열 치환을 직접 처리한다.
- Spring AI 대안: `PromptTemplate`, `SystemPromptTemplate`, Resource 기반 템플릿
- 판단:
  - 대체 가능: **Yes**
  - 즉시 전환 우선순위: **Medium**
- 이유:
  - 지금 구현도 단순하고 안정적이다. 다만 표준 템플릿 API로 옮기면 유지보수와 확장이 쉬워진다.

### 2. RAG 조립(검색+프롬프트 주입)

- 현재: `ContextRetriever`와 `ChatService`에서 직접 조립한다.
- Spring AI 대안: `QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor`
- 판단:
  - 대체 가능: **부분적으로 Yes**
  - 즉시 전환 우선순위: **Medium-Low**
- 이유:
  - Advisor로 표준화는 가능하다. 하지만 지금은 요청당 1회 호출과 휴리스틱 라우팅 제약이 강하다.
  - 현재 구조에서는 검색 품질 튜닝, 특히 임계치·필터·배치 조회를 먼저 손보는 편이 효과가 크다.

### 3. 채팅 메모리 관리

- 현재: `ChatService`에서 히스토리 저장과 윈도우 관리를 직접 처리한다.
- Spring AI 대안: `MessageChatMemoryAdvisor`, `PromptChatMemoryAdvisor`, `VectorStoreChatMemoryAdvisor`
- 판단:
  - 대체 가능: **Yes**
  - 즉시 전환 우선순위: **Low-Medium**
- 이유:
  - 현재 정책은 요약 LLM 제거와 1회 호출 고정이다. 이 조건에서는 직접 구현이 단순하고 제어하기 쉽다.

### 4. 관측성(메트릭/트레이스)

- 현재: 커스텀 Micrometer 카운터 일부를 추가해 사용한다.
- Spring AI 대안: ChatClient/Advisor/VectorStore 관측 자동 계측 + Actuator
- 판단:
  - 대체 가능: **Yes**
  - 즉시 전환 우선순위: **High**
- 이유:
  - 운영 중 병목과 품질 이슈를 추적하려면 표준 계측이 유리하다. 토큰, 지연시간, 벡터 쿼리 지표를 함께 보는 쪽이 낫다.

### 5. 벡터 검색 품질 파라미터

- 현재: `topK` 중심으로 검색하며 threshold는 적용하지 않는다.
- Spring AI 대안: `SearchRequest.Builder.similarityThreshold(...)`
- 판단:
  - 대체 가능: **Yes (즉시 적용 권장)**
  - 즉시 전환 우선순위: **High**
- 이유:
  - 유사도가 낮은 문서 유입을 줄이면 답변 품질 개선 효과가 크다.

### 6. 평가(Eval)

- 현재: 라우팅 eval 테스트 셋을 도입했다.
- Spring AI 대안: `Evaluator`, `FactCheckingEvaluator` 기반 평가
- 판단:
  - 대체 가능: **Yes**
  - 즉시 전환 우선순위: **Medium**
- 이유:
  - 라우팅뿐 아니라 응답과 근거가 맞는지까지 자동 점검이 가능하다.

## 핵심 결론

1. 현재 구조는 단순하고 제어하기 쉽다는 점에서 타당하다.
2. Spring AI 문서 기준으로 바로 이득을 볼 수 있는 항목은 세 가지다.
   - `similarityThreshold` 도입
   - Vector/Chat 관측성 표준 계측 강화
   - 배치 조회로 retrieval I/O 절감
3. `ChatClient + Advisors`로 전면 전환도 가능하다. 다만 지금은 단계적으로 적용하는 편이 더 안전하다.

## 개선 방향 (문서 우선 합의안)

### Phase 1 (즉시, 리스크 낮음)

- 목표: 응답 품질과 지연시간을 빠르게 개선한다.
- 작업:
  - `SearchRequest`에 `similarityThreshold` 외부 설정화
  - chunk id 다건 조회 API 추가(`findContentsByIds`)로 DB 왕복 축소
  - `GeminiAdapter`의 dead path 제거(라우팅/요약 목적 분기 정리)
- 기대효과:
  - RAG 정밀도 개선
  - 평균 응답시간 감소
  - 코드 복잡도 감소

### Phase 2 (단기)

- 목표: 품질 회귀를 막는다.
- 작업:
  - 라우팅 eval 케이스 50+ 확장
  - RAG 응답 평가(근거 일치성) 테스트 추가
  - 품질 지표 기준선 정의(`rag_empty_rate`, `source_coverage_rate`)

### Phase 3 (중기)

- 목표: Spring AI 표준 패턴에 맞춘다.
- 작업:
  - `ChatClient` 기반 생성 경로 전환
  - 필요 시 `QuestionAnswerAdvisor` 또는 `RetrievalAugmentationAdvisor` 점진 도입
  - Memory Advisor 도입 여부는 1회 호출 정책과 함께 재평가

## 리스크/주의사항

- `similarityThreshold`를 너무 높이면 recall이 크게 떨어질 가능성이 있다.
  - 권장: 0.62~0.72 범위에서 A/B
- Advisor 전환은 구조를 단순하게 만들 수 있지만, 초기 마이그레이션 비용이 있다.
- 관측 로그에 프롬프트나 완성문을 노출하려면 민감정보 정책을 먼저 검토해야 한다.

## 다음 코드 반영 후보 (우선순위)

1. `MongoVectorAdapter`에 `similarityThreshold` 적용 + 설정화
2. `ResumePersistencePort` 다건 조회 API 추가 및 `ContextRetriever` 적용
3. `GeminiAdapter` 라우팅/요약 분기 및 불필요 속성 정리

## 참고 문서 (Spring AI 공식)

- Chat Client API: https://docs.spring.io/spring-ai/reference/api/chatclient.html
- Advisors API: https://docs.spring.io/spring-ai/reference/api/advisors.html
- Retrieval Augmented Generation: https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html
- Chat Memory: https://docs.spring.io/spring-ai/reference/api/chat-memory.html
- Prompts / PromptTemplate: https://docs.spring.io/spring-ai/reference/api/prompt.html
- Structured Output Converter: https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html
- Evaluation Testing: https://docs.spring.io/spring-ai/reference/api/testing.html
- Observability: https://docs.spring.io/spring-ai/reference/observability/index.html
- SearchRequest.Builder Javadoc (similarityThreshold): https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/vectorstore/SearchRequest.Builder.html
