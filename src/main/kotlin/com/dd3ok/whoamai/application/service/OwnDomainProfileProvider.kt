package com.dd3ok.whoamai.application.service

import org.springframework.stereotype.Component

@Component
class OwnDomainProfileProvider {

    fun serviceProfile(): String =
        "who-am-ai는 유인재가 만든 AI 기반 프로필 서비스입니다. " +
            "Kotlin과 Spring Boot WebFlux 기반으로 구성되어 있으며, Spring AI와 Google Gemini 3.8 Flash로 대화 응답을 생성합니다. " +
            "이력 정보 검색에는 Gemini 임베딩과 MongoDB Atlas Vector Search를 사용하고, 운영 관측에는 Spring Boot Actuator와 Micrometer를 사용합니다. " +
            "질문 처리에는 규칙 기반 검색과 벡터 검색을 함께 사용합니다. " +
            "포트폴리오 자료는 답변할 때 검색해 참고하며, 이 자료로 모델을 별도 학습시키지는 않습니다. " +
            "짧은 후속 질문은 앞선 사용자 질문의 프로젝트나 회사 맥락으로 검색하고, 참고 자료·답변 조각·완료·오류를 WebSocket 이벤트로 전달합니다. " +
            "서버 발급 익명 세션으로 대화 소유권을 확인하고 MongoDB로 요청 제한과 기록 저장 충돌을 처리합니다. " +
            "로컬 회귀 검증과 실제 외부 API·Atlas 평가는 구분합니다. 운영 트래픽이나 응답 품질의 정량 성과는 기록되어 있지 않습니다."
}
