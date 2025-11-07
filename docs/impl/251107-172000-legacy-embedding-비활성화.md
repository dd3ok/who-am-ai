# Implement — Legacy Embedding Adapter 비활성화

## 변경 요약
- `GeminiApiEmbeddingAdapter`에 `@ConditionalOnProperty(prefix = "whoamai.legacy-embedding", name = ["enabled"], havingValue = "true")`를 추가하여 기본적으로 빈이 생성되지 않도록 했다.
- `application.yml`에 `whoamai.legacy-embedding.enabled: false` 기본값을 선언해 의도적인 비활성 상태임을 문서화했다.

## Why / Scope
- Why: Spring AI 도입 이후에도 레거시 임베딩 어댑터가 빈 생성을 시도하면서 BeanCreationException으로 전체 부팅이 중단되었기 때문.
- Scope: 레거시 어댑터 활성 조건 제어 및 설정값 추가만 수행했다.
- 참조: /docs/plan+design/251107-171934-legacy-embedding-비활성화.md

## Plan 대비 변경 사항 (Drift)
- 없음. Plan에서 정의한 조건부 활성화 전략을 그대로 적용했다.

## 코드 주요 포인트
- `@ConditionalOnProperty` 사용으로 프로퍼티가 true일 때만 `GeminiApiEmbeddingAdapter` 빈 생성.
- 신규 프로퍼티는 `whoamai.legacy-embedding.enabled`로, 향후 필요 시 true로 전환해 레거시 어댑터를 재사용할 수 있다.

## 배치/운영 플로우
- 기본값(false)에서는 Spring AI EmbeddingModel만 사용하며, 레거시 어댑터는 로딩되지 않는다.
- 운영 환경에서 레거시 경로가 필요하면 해당 프로퍼티를 true로 재배포하면 된다.

## 테스트 시나리오 & 예상 결과 (실행하지 않음)
- 시나리오 1: 기본 설정(값 false)으로 애플리케이션 기동 → `GeminiApiEmbeddingAdapter` 빈 미생성, BeanCreationException 미발생.
- 시나리오 2: `whoamai.legacy-embedding.enabled=true`로 기동 → 레거시 어댑터 생성, 필요한 경우 수동 점검.
- 엣지 케이스: 프로퍼티 누락 시 `matchIfMissing=false`로 인해 기본적으로 빈이 생성되지 않음.

## 작업 체크리스트
- [✅] 조건부 빈 구성 적용
- [✅] application.yml에 기본 값 추가
- [✅] Plan/Implement 문서화
