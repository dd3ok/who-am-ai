# Plan+Design — Legacy Embedding Adapter 비활성화

## 목적 / 문제정의
- Spring AI VectorStore 자동 구성을 도입한 이후에도 `GeminiApiEmbeddingAdapter` 빈이 생성되면서 BeanCreationException이 발생한다.
- 현재 애플리케이션 로직에서 `EmbeddingPort` 구현을 사용하지 않으므로, 실패 원인을 제거하고 불필요한 빈 생성을 막는다.

## 범위(Scope)
- 포함: `GeminiApiEmbeddingAdapter` 활성 조건 제어(예: 프로퍼티/프로파일), 필요 시 설정값 추가.
- 제외: Spring AI VectorStore/EmbeddingModel 구성 자체, 다른 Gemini SDK 설정.

## 입력 / 출력 / 의존성
- 입력: 최신 에러 로그, `GeminiApiEmbeddingAdapter` 코드, `application.yml`.
- 출력: 조건부 빈 구성(또는 비활성화) 및 구현 문서.
- 참조: 내부 문서(기존 Spring AI 전환 계획) see: docs/plan/251106-180038-임베딩-빈-구성.md.
- 환경: Spring Boot 3.x, Kotlin, Spring Framework Conditional 애노테이션 사용 가능.

## 데이터 흐름(DFD) / 시퀀스
1. 애플리케이션 기동 시 Component 스캔 → `GeminiApiEmbeddingAdapter` 빈 생성 시도.
2. 생성 실패 시 컨텍스트 전체가 중단 → VectorStore 구성도 영향.
3. 조건부 활성화를 적용하면 프로퍼티가 참일 때만 생성되어, 기본값으로는 스킵된다.

## 설계(아키텍처 / 모듈 / 의존성)
- `@ConditionalOnProperty`(또는 `@Profile`)로 빈을 감싼다.
- 프로퍼티 키 예: `whoamai.legacy-embedding.enabled` (기본 false, 필요 시 true).
- Why: 추후 필요 시 속성으로 재활성화할 수 있으며, 삭제 대신 Deprecated 정책을 유지한다.

## 리스크 / 가정
- 리스크: 향후 해당 어댑터를 의존하는 모듈이 추가될 경우 프로퍼티를 활성화해야 함.
- 가정: 현재 코드베이스 어디에서도 `EmbeddingPort`를 주입받지 않는다(검색으로 확인 예정).

## 완료 기준(DoD)
- 애플리케이션 기본 구동 시 `GeminiApiEmbeddingAdapter`가 생성되지 않아 BeanCreationException이 사라진다.
- 프로퍼티를 true로 설정하면 Bean이 다시 활성화된다.
- Why/Scope 및 테스트 시나리오가 문서화된다.

## Why / Scope 기록
- Why: 불필요한 Bean이 전체 부팅을 막는 문제를 해결하고, 필요 시 재사용 가능성을 남기기 위해.
- Scope: 해당 어댑터 및 관련 설정에 제한.
