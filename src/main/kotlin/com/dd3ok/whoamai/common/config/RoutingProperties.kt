package com.dd3ok.whoamai.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 작업 목적: 기본 RAG 전략에서 일반 대화로 우회할 예외 키워드를 외부 설정으로 관리한다.
 * 주요 로직: `routing.non-resume-keywords` 목록을 주입 받아 Intent Decider가 참조할 수 있도록 제공한다.
 */
@Configuration
@ConfigurationProperties(prefix = "routing")
data class RoutingProperties(
    var nonResumeKeywords: List<String> = emptyList()
)
