# Implement — 경력 질의 컨텍스트 복구

## 변경 요약
- `MongoVectorAdapter.findChunkById`에서 `_id` 대신 `metadata.chunk_id`를 기준으로 MongoDB 문서를 조회하도록 수정했다.
- Reactive 쿼리를 `Dispatchers.IO` 컨텍스트에서 실행하고, 매칭 실패 시 재색인 필요성을 경고 로그로 남기도록 보강했다.

## Why / Scope
- Why: 규칙 기반으로 생성한 chunk ID로 `_id`를 조회하던 기존 구현은 VectorStore가 부여한 ObjectId와 달라 항상 `null`을 반환해 RAG 컨텍스트가 비어 있었다.
- Scope: VectorStore 접근 계층만 수정하면 상위 비즈니스 로직을 건드리지 않고 문제를 해결할 수 있어 해당 파일만 변경했다. (see: Principles#Why/Scope기록)

## Plan 대비 변경 사항 (Drift)
- 설계 문서(`/docs/plan+design/251107-212516-경력-rag-컨텍스트.md`)에서 정의한 대로 `metadata.chunk_id` 조건 쿼리를 도입했으며, 추가로 관찰성 향상을 위해 WARN 로그를 추가했다.

## 코드 주요 포인트
- `Query(Criteria.where("metadata.chunk_id").is(id))`를 사용해 chunk ID로 정확히 일치하는 문서를 하나만 조회한다.
- 조회 결과가 없으면 `null`을 반환하되, 즉시 경고 로그를 남겨 재색인 필요 여부를 파악할 수 있게 했다.

## 배치/운영 플로우
- `ManageResumeService.reindexResumeData()` 실행 시 기존과 동일하게 전체 컬렉션을 재색인하며, 이후 규칙 기반 질문은 MongoDB에서 chunk 내용을 바로 읽어온다.

## 테스트 시나리오 & 예상 결과 (실행하지 않음)
- 시나리오 1: "이력은?" → `ContextRetriever`가 `experiences` 규칙을 매칭하고 각 회사 chunk 내용을 반환, ChatService가 RAG 프롬프트를 사용한다.
- 시나리오 2: "경력 알려줘" → 동일하게 `experiences` 컨텍스트가 채워져 Vector 검색 fallback이 발생하지 않는다.
- 엣지 케이스: `metadata.chunk_id`가 누락된 문서를 조회할 경우 WARN 로그가 출력되고 `null`이 반환된다.

## 작업 체크리스트
- [✅] chunk ID 기준 MongoDB 조회 구현
- [✅] 조회 실패 시 관찰성 로그 추가
- [ ] 후속 VectorStore 인덱스 정합성 점검 (필요 시)
