package com.dd3ok.whoamai.application.service

import org.springframework.stereotype.Component

@Component
class OwnDomainProfileProvider {

    fun serviceProfile(): String =
        "who-am-ai는 유인재가 만든 AI 기반 프로필 서비스입니다. " +
            "Kotlin과 Spring Boot WebFlux 기반으로 구성되어 있으며, Spring AI와 Google Gemini를 사용해 대화 응답을 생성합니다. " +
            "이력 정보 검색에는 MongoDB Atlas Vector Search를 사용하고, 운영 관측에는 Spring Boot Actuator와 Micrometer를 사용합니다. " +
            "질문 처리에는 규칙 기반 검색과 벡터 검색을 함께 사용합니다."
}
