package com.dd3ok.whoamai.application.service

import org.springframework.stereotype.Component

@Component
class OwnDomainProfileProvider {

    fun serviceProfile(): String =
        "who-am-ai는 유인재가 만든 AI 기반 프로필 서비스입니다. " +
            "Kotlin과 Spring Boot WebFlux 기반으로 구성되어 있으며, Spring AI를 통해 Google Gemini 3.8 Flash(gemini-3.8-flash)로 대화 응답을 생성하고 Gemini 임베딩(gemini-embedding-2, 768차원)과 MongoDB Atlas Vector Search(resume_chunks_embedding2 컬렉션, 기본 유사도 임계값 0.65)로 이력 정보를 검색합니다. " +
            "질문 처리는 2단계 하이브리드 라우팅을 사용합니다: Tier 1 규칙 기반 검색에 매칭되지 않는 세부 질문과 짧은 후속 질문은 비생성형 Gemini Decisions API(gemini-decision-1, GeminiDecisionAdapter, POST /v1beta/decisions 기반 1 choice + N noul)로 카탈로그·주제 선택(PortfolioCatalogSelectorPort, ConversationTopicPort)을 수행하고, 장애 또는 비활성 시 GeminiAdapter(JSON Schema)와 LLMRouter·MongoDB Atlas Vector Search·질의 토큰·청크 타입 가중치 기반 휴리스틱 리랭킹 경로로 폴백합니다. " +
            "근거 자료가 비어 있으면 모델을 호출하지 않고 차단해 환각을 막으며, 참고 자료·답변 조각·완료·오류를 WebSocket 이벤트(/ws/chat/events)로 전달합니다. " +
            "화면의 추천·예시 질문 문구 자체는 별도 질문 DB에서 조회하는 것이 아니라 클라이언트(프런트엔드)의 정적 질문이며(who-am-ai 백엔드에는 질문 목록 DB나 조회 API가 없음), 질문에 대한 이력서 근거 데이터와 답변 캐시·대화 내용은 MongoDB를 조회합니다. " +
            "원본 resume.json은 ResumeChunkingService로 분할·임베딩(gemini-embedding-2)해 MongoDB Atlas(resume_chunks_embedding2 컬렉션)에 저장합니다. " +
            "독립 질문은 먼저 MongoDB 답변 캐시(MongoChatAnswerCacheAdapter, chat_answer_cache 컬렉션 및 인메모리 캐시)를 조회해 캐시 히트 시 페이싱 스트리밍(CachedAnswerStreamPacer)으로 응답하고(기동 시 MongoIndexWarmupRunner가 세션·캐시 TTL 인덱스를 비동기 워밍업), 캐시 미스나 문맥 의존 후속 질문은 MongoDB에서 관련 이력 청크를 조회해 답변을 생성합니다. " +
            "현재 대화 내용(이전 질문·답변 이력)은 매 질문 요청 시 세션 ID(uuid) 기준으로 MongoDB(chat_histories 컬렉션, ChatHistoryDocument)를 조회(ChatHistoryRepository.findByUserId)해 최근 대화 윈도우(ChatHistoryWindow)를 문맥으로 불러오고, 답변 완료 후 새 질문과 답변을 MongoDB chat_histories 컬렉션에 revision 필드 기반 낙관적 락 조건부 업데이트와 재시도로 저장(appendMessages)합니다. " +
            "모델 폴백(GeminiAdapter)은 who-am-ai.ai.chat.models 우선순위 목록을 기준으로 첫 토큰 전 429(Rate Limit)나 빈 스트림 발생 시 해당 모델을 60초 쿨다운(rate-limit-cooldown-ms: 60000) 처리하고 300ms 백오프 후 다음 모델로 전환하는 구조입니다(현재 기본 설정은 gemini-3.8-flash 단일 모델). " +
            "접근 제어와 요청 제한(ChatAccessService)은 POST /api/chat/sessions로 24시간 유효 익명 세션 토큰을 발급하고, MongoDB 분 단위 카운터(chat_sessions, chat_request_buckets 컬렉션)로 세션당 분당 10회·IP당 분당 30회(세션 발급 5회)·전체 분당 120회(세션 발급 60회) 제한을 적용합니다. " +
            "테스트와 평가는 Gradle/JUnit 태그로 3단계 분리했습니다: (1) 기본 ./gradlew test는 live·mongo 태그를 제외한 오프라인 회귀 테스트(PortfolioRetrievalEvalTest 등)를 실행하고, (2) ./gradlew mongoTest는 TEST_MONGO_URI 로컬 MongoDB에서 원자적 요청 제한과 revision 충돌 재시도를 검증하며, (3) ./gradlew liveEval은 EVAL_MONGO_URI 임시 DB와 실제 GEMINI_API_KEY로 임베딩 검색 적중률 및 GeminiAnswerJudge 기반 답변 품질을 평가한 뒤 정리합니다. " +
            "운영 관측은 Spring Boot Actuator(health, info, metrics, prometheus)와 Micrometer 타이머·카운터(whoamai.chat.request.total, whoamai.chat.gemini.first_token.duration, whoamai.rag.context.size, whoamai.chat.gemini.rate_limited.total 등)로 수집합니다. 운영 트래픽이나 응답 품질의 정량 성과 수치는 별도 기록되어 있지 않습니다."
}
