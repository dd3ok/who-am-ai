package com.dd3ok.whoamai.application.service

import org.springframework.stereotype.Component

@Component
class OwnDomainProfileProvider {

    fun serviceProfile(): String =
        "who-am-ai는 유인재가 만든 AI 기반 프로필 서비스입니다. " +
            "Kotlin과 Spring Boot WebFlux 기반으로 구성되어 있으며, Spring AI를 통해 Google Gemini 3.8 Flash(gemini-3.8-flash)로 대화 응답을 생성하고 Gemini 임베딩(gemini-embedding-2, 768차원)과 MongoDB Atlas Vector Search(resume_chunks_embedding2 컬렉션, 기본 유사도 임계값 0.65)로 이력 정보를 검색합니다. " +
            "질문 처리에는 규칙 기반 검색과 벡터 검색, 질의 토큰·청크 타입 가중치 기반 휴리스틱 리랭킹을 함께 사용합니다. 근거 자료가 비어 있으면 모델을 호출하지 않고 차단해 환각을 막습니다. " +
            "짧은 후속 질문은 JSON Schema 기반 주제 선택(ConversationTopicPort)과 앞선 사용자 질문의 프로젝트·회사 맥락을 활용해 검색을 보완하고, 참고 자료·답변 조각·완료·오류를 WebSocket 이벤트(/ws/chat/events)로 전달합니다. " +
            "모델 폴백(GeminiAdapter)은 who-am-ai.ai.chat.models 우선순위 목록을 기준으로 첫 토큰 전 429(Rate Limit)나 빈 스트림 발생 시 해당 모델을 60초 쿨다운(rate-limit-cooldown-ms: 60000) 처리하고 300ms 백오프 후 다음 모델로 전환하는 구조입니다(현재 기본 설정은 gemini-3.8-flash 단일 모델). " +
            "접근 제어와 요청 제한(ChatAccessService)은 POST /api/chat/sessions로 24시간 유효 익명 세션 토큰을 발급하고, MongoDB 분 단위 카운터로 세션당 분당 10회·IP당 분당 30회(세션 발급 5회)·전체 분당 120회(세션 발급 60회) 제한을 적용합니다. " +
            "대화 이력 저장 충돌은 MongoDB ChatHistoryDocument의 revision 필드를 이용한 낙관적 락 조건부 업데이트와 재시도로 처리합니다. " +
            "테스트와 평가는 Gradle/JUnit 태그로 3단계 분리했습니다: (1) 기본 ./gradlew test는 live·mongo 태그를 제외한 오프라인 회귀 테스트(PortfolioRetrievalEvalTest 등)를 실행하고, (2) ./gradlew mongoTest는 TEST_MONGO_URI 로컬 MongoDB에서 원자적 요청 제한과 revision 충돌 재시도를 검증하며, (3) ./gradlew liveEval은 EVAL_MONGO_URI 임시 DB와 실제 GEMINI_API_KEY로 임베딩 검색 적중률 및 GeminiAnswerJudge 기반 답변 품질을 평가한 뒤 정리합니다. " +
            "운영 관측은 Spring Boot Actuator(health, info, metrics, prometheus)와 Micrometer 타이머·카운터(whoamai.chat.request.total, whoamai.chat.gemini.first_token.duration, whoamai.rag.context.size, whoamai.chat.gemini.rate_limited.total 등)로 수집합니다. 운영 트래픽이나 응답 품질의 정량 성과 수치는 별도 기록되어 있지 않습니다."
}
