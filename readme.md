# AI 이력서 챗봇 (AI Profile Chatbot)

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.x-blue.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MongoDB Atlas](https://img.shields.io/badge/MongoDB-Atlas%20Vector%20Search-green.svg)](https://www.mongodb.com/products/platform/atlas-vector-search)
[![Gemini API](https://img.shields.io/badge/Google-Gemini%20API-blue.svg)](https://ai.google.dev/)

이력서를 기반으로, 채용 담당자나 다른 사람들이 자연어 질문을 통해 궁금한 점을 물어보고 답변을 받을 수 있는 대화형 AI 챗봇입니다.

단순한 의미 검색을 넘어, **결정론적인 규칙 기반 검색**과 **LLM 기반의 확률적 벡터 검색**을 결합한 하이브리드 접근법을 채택하여, 답변의 신뢰성과 유연성을 모두 확보하는 것을 목표로 합니다.

## 주요 기능 (Key Features)

* **'선 규칙, 후 LLM' 아키텍처:** "학력 알려줘", "취미 뭐야?" 와 같이 명확한 질문은 100% 예측 가능한 **규칙**으로 먼저 처리하여 빠르고 정확한 답변을 보장합니다.
* **지능형 컨텍스트 검색:** 규칙으로 처리할 수 없는 복잡한 질문("MSA 전환 경험에 대해 어떻게 생각해?")은 **LLM 라우터**의 분석을 '힌트' 삼아 **벡터 검색**을 수행하여, 문맥에 가장 적합한 정보를 찾아냅니다.
* **자연스러운 1인칭 답변:** "그는 ~했습니다"가 아닌, "저는 ~했습니다"와 같이 자신의 이력서를 직접 설명하는 자연스러운 1인칭 시점으로 대화합니다.
* **최적화된 프롬프트:** 각 작업(라우팅, 답변 생성, 요약)에 맞는 별도의 프롬프트와 설정을 사용하여 API 호출 효율을 높이고 토큰 비용을 절감했습니다.
* **견고한 ID 생성:** 프로젝트 제목 등에 특수문자가 포함되어도 문제없이 처리할 수 있도록 ID 생성 규칙을 강화하여 안정성을 높였습니다.
* **실시간 스트리밍 응답:** `WebFlux`와 `WebSocket`을 사용하여 AI가 생성하는 답변을 실시간으로 사용자에게 전달하여 UX를 개선했습니다.

## 아키텍처 및 기술 스택

이 서비스는 **LLM 기반의 지능형 라우터(LLM Router)**가 중심이 되는 고도화된 RAG 파이프라인을 기반으로 설계되었습니다.

**데이터 흐름:**
사용자 질문 ➡️ 1. 규칙 기반 검색 (ContextRetriever) ➡️ [규칙 일치 시] 컨텍스트 반환 ➡️ [불일치 시] 2. LLM 라우터 (힌트 획득) ➡️ 3. 벡터 검색 ➡️ 4. 프롬프트 엔지니어링 및 답변 생성 ➡️ 실시간 스트리밍

**기술 스택:**
* **Backend:** Kotlin, Spring Boot 3
* **AI / LLM:** Google Gemini API (텍스트 생성, 임베딩, 라우팅)
* **Database:** MongoDB Atlas (Vector Search 기능 활용)
* **Asynchronous:** Spring WebFlux / Project Reactor (스트리밍 응답 처리)
* **Libraries:** Jackson (JSON 처리), Spring Data MongoDB

## 동작 원리 (How It Works)

이 시스템의 핵심은 **"지식 학습(Ingestion)"**과 **"답변 생성(Generation)"** 두 단계로 나뉩니다.

### 1. 지식 학습 과정 (Data Ingestion)

애플리케이션이 시작될 때, `resume.json` 파일의 정보를 AI가 이해하기 좋은 형태로 가공하여 데이터베이스에 저장합니다.

1.  **데이터 청킹 (Chunking):** 이력서 정보를 '요약', '경력', '프로젝트' 등 의미 있는 단위로 나눕니다. 각 프로젝트는 개별 청크로 분리되어 검색 정확도를 높입니다.
2.  **임베딩 및 인덱싱:** 생성된 각 청크는 Google Gemini API를 통해 의미를 담은 숫자 벡터(Embedding)로 변환됩니다. 이 벡터는 Chunk의 원문, 그리고 필터링에 사용할 메타데이터(`company`, `skills` 등)와 함께 MongoDB Atlas에 저장 및 인덱싱됩니다.

### 2. 답변 생성 과정 (Generation)

사용자의 질문에 최적의 답변을 생성하는 과정은 **'선(先) 규칙, 후(後) LLM'** 원칙을 따릅니다.

1.  **1단계: 규칙 기반 우선 처리 (ContextRetriever)**
    `ContextRetriever`가 가장 먼저 사용자 질문을 확인합니다. "학력", "취미", "경력", 특정 프로젝트 제목 등 명확한 키워드가 포함된 경우, LLM을 호출할 필요 없이 즉시 데이터베이스에서 해당 정보를 찾아 반환합니다. 이 '빠른 길(Fast Lane)'은 시스템의 신뢰성과 속도를 보장합니다.

2.  **2단계: LLM 라우터의 '힌트' 획득**
    규칙에 맞는 내용이 없다면, `LLMRouter`가 사용자 질문을 분석하여 `RESUME_RAG`(이력서 질문) 또는 `NON_RAG`(일반 대화)라는 '힌트'와 함께 검색에 도움이 될 키워드를 추출합니다.

3.  **3단계: 벡터 검색 또는 일반 대화**
    `ChatService`는 `ContextRetriever`가 반환한 결과를 최종 기준으로 삼습니다.
    * **컨텍스트 존재 시 (규칙/벡터 검색 성공):** 검색된 정보를 바탕으로 RAG 파이프라인을 실행합니다.
    * **컨텍스트 부재 시:** 일반 대화로 판단하고, 이전 대화 기록만을 사용하여 자연스러운 답변을 생성합니다.

4.  **4단계: 프롬프트 엔지니어링 및 답변 생성**
    * 상황에 맞는 데이터(대화 기록, 검색된 컨텍스트)와 사용자의 질문, 그리고 최적화된 프롬프트 템플릿을 조합하여 최종 프롬프트를 만듭니다.
    * 최종 프롬프트를 Google Gemini API에 전달하여, 자연스러운 1인칭 시점의 답변을 생성하고 사용자에게 스트리밍 방식으로 전송합니다.

## 시작하기 (Getting Started)

### 사전 준비
*   Java (JDK) 17 이상
*   Gradle
*   MongoDB Atlas 계정
*   Google AI Studio (Gemini) API Key

### 1. 리포지토리 클론
```bash
git clone [저장소_URL]
cd [프로젝트_폴더]
```

### 2. MongoDB Atlas 설정
1.  Atlas에서 데이터베이스를 생성합니다. (예: `who-am-ai`)
2.  데이터베이스에 대한 연결 문자열(Connection String)을 복사합니다.
3.  `resume_chunks` 컬렉션을 생성하고, **`Search` 탭**에서 새로운 검색 인덱스를 생성합니다.
    *   **Index Name:** `vector_index`
    *   **Editor:** `JSON Editor`
    *   아래의 JSON 정의를 붙여넣습니다.
    ```json
    {
      "fields": [
        {
          "type": "vector",
          "path": "content_embedding",
          "numDimensions": 768,
          "similarity": "cosine"
        },
        {
          "type": "filter",
          "path": "chunk_type"
        },
        {
          "type": "filter",
          "path": "company"
        },
        {
          "type": "filter",
          "path": "skills"
        }
      ]
    }
    ```

### 3. 애플리케이션 설정
`src/main/resources/application.properties` (또는 `.yml`) 파일에 아래 내용을 추가하고 자신의 정보로 교체합니다.

```properties
# Gemini API
gemini.api.key=여기에_당신의_GEMINI_API_KEY를_입력하세요

# MongoDB Atlas
spring.data.mongodb.uri=여기에_당신의_MONGODB_ATLAS_연결_문자열을_입력하세요
```

### 4. 이력서 데이터 수정
`src/main/resources/resume.json` 파일을 열어 자신의 이력서 내용으로 수정합니다.

### 5. 애플리케이션 실행
```bash
./gradlew bootRun
```
이제 애플리케이션이 실행되고, 시작 로그에 Chunk 생성 및 인덱싱 과정이 출력됩니다.

## 향후 개선 방향
* **하이브리드 검색 도입:** 현재의 의미 기반 벡터 검색에 키워드 기반 검색(Full-text search)을 결합하여 검색 정확도 향상.
* **재순위화(Re-ranking) 단계 추가:** 검색된 컨텍스트들을 더 정교한 모델로 재평가하여 LLM에게 전달할 최종 컨텍스트의 품질 개선.
* **평가 프레임워크 도입:** 답변의 품질을 객관적으로 측정하고, 변경 사항이 성능에 미치는 영향을 추적하는 시스템 구축.
* **Agentic RAG 도입:** 단일 RAG 호출로 답변이 불충분할 경우, 스스로 추가 검색이나 다른 도구를 호출하여 답변을 보강하는 에이전트 아키텍처 도입.