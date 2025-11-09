package com.dd3ok.whoamai.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
@EnableWebFlux
class CorsConfig : WebFluxConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**") // API 경로에 대해서만 CORS 적용
            .allowedOrigins("https://dd3ok.github.io", "http://localhost:3000") // GitHub Pages와 로컬 개발용 프론트엔드 주소 허용
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 허용할 HTTP 메소드
            .allowedHeaders("*") // 모든 헤더 허용
            .allowCredentials(true) // Credential 허용
            .maxAge(3600) // pre-flight 요청 캐시 시간 (초)
    }
}