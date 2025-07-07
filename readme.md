# AI 이력서 챗봇 (AI Profile Chatbot)

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.x-blue.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MongoDB Atlas](https://img.shields.io/badge/MongoDB-Atlas%20Vector%20Search-green.svg)](https://www.mongodb.com/products/platform/atlas-vector-search)
[![Gemini API](https://img.shields.io/badge/Google-Gemini%20API-blue.svg)](https://ai.google.dev/)

이 프로젝트는 개인의 이력서를 기반으로, 채용 담당자나 다른 사람들이 자연어 질문을 통해 궁금한 점을 물어보고 답변을 받을 수 있는 대화형 AI 챗봇입니다.

단순한 키워드 매칭을 넘어, LLM(거대 언어 모델)이 직접 질문의 문맥과 의도를 파악하여 최적의 답변을 생성하는 **지능형 RAG(Retrieval-Augmented Generation, 검색 증강 생성)** 파이프라인을 중심으로 구현되었습니다.

## 주요 기능 (Key Features)

* **LLM 라우터를 통한 동적 의도 분석:** 사용자의 질문을 LLM이 직접 분석하여 **'이력서 질문'**과 **'일반 대화'**로 분류하고, 질문에 담긴 핵심 정보(회사명, 기술 스택 등)를 동적으로 추출합니다.
* **고품질 컨텍스트 검색:** "지마켓에서 어떤 보안 프로젝트를 했나요?"와 같이 복잡한 질문이 들어와도, LLM 라우터가 추출한 정보를 바탕으로 Vector Search 필터를 동적으로 구성하여 정확한 정보를 찾아냅니다.
* **유연한 대화 능력:** 이력서와 무관한 질문("토마토는 몸에 좋아?")이나 이전 대화를 기억해야 하는 질문("내가 누구게?")에 대해서도 자연스럽게 대화합니다.
* **대화 흐름 유지:** 긴 대화가 오가더라도, 자동으로 이전 대화 내용을 요약하고 기억하여 맥락에 맞는 대화를 이어갑니다.
* **실시간 스트리밍 응답:** `WebFlux`와 `WebSocket`을 사용하여 AI가 생성하는 답변을 실시간으로 사용자에게 전달하여 UX를 개선했습니다.

## 아키텍처 및 기술 스택

이 서비스는 **LLM 기반의 지능형 라우터(LLM Router)**가 중심이 되는 고도화된 RAG 파이프라인을 기반으로 설계되었습니다.

**데이터 흐름:**
`사용자 질문` ➡️ `1. LLM 라우터 (의도 분석 및 정보 추출)` ➡️ `2. RAG 파이프라인 (컨텍스트 검색)` ➡️ `3. 프롬프트 엔지니어링 및 답변 생성` ➡️ `실시간 스트리밍`

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

사용자의 질문을 받아 최적의 답변을 생성하는 과정은 **'Three-Agent' 아키텍처**를 따릅니다.

1.  **1단계: 의도 분류 (Intent Classification)**
    `ChatService`의 `classifyQueryType` 함수가 사용자의 질문을 분석하여 아래 세 가지 중 하나의 '의도'로 분류합니다.
    *   **`CHIT_CHAT`:** 간단한 인사, AI 자신의 정체성에 대한 질문.
    *   **`RESUME_RAG`:** 이력서와 관련된 명시적인 키워드(경력, 프로젝트, 기술 등)가 포함된 질문.
    *   **`GENERAL_CONVERSATION`:** 위 두 경우에 해당하지 않는 모든 일반 대화 및 후속 질문.

2.  **2단계: 의도별 데이터 구성 (Context & History Assembly)**
    분류된 의도에 따라 AI에게 전달할 **'대화 기록'**과 **'참조 정보(RAG 컨텍스트)'**를 다르게 구성합니다.
    *   **`CHIT_CHAT` 처리:** AI의 정체성 혼란을 막고 대화 흐름을 유지하기 위해, 이전 대화 기록 중 AI의 긴 답변(오염원)을 필터링한 **'정제된 기록'** 만 사용합니다. RAG 컨텍스트는 사용하지 않습니다.
    *   **`GENERAL_CONVERSATION` 처리:** 이전 대화를 모두 기억해야 하므로 **'전체 대화 기록'** 을 사용합니다. RAG 컨텍스트는 사용하지 않습니다.
    *   **`RESUME_RAG` 처리:** 이력서에 대해 정확히 답변해야 하므로 **'전체 대화 기록'** 과 **'RAG 검색 결과'** 를 모두 사용합니다.

3.  **3단계: 프롬프트 엔지니어링 및 답변 생성 (Prompt & Generation)**
    *   구성된 데이터(대화 기록, 참조 정보)와 사용자의 질문, 그리고 AI의 역할을 정의하는 **시스템 지침(System Instruction)**을 하나의 프롬프로 조합합니다.
    *   최종 프롬프트를 Google Gemini API에 전달하여, 자연스러운 문장 형태의 답변을 생성하고 사용자에게 스트리밍 방식으로 전송합니다.

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