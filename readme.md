# AI 이력서 챗봇 (AI Profile Chatbot)

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.x-blue.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MongoDB Atlas](https://img.shields.io/badge/MongoDB-Atlas%20Vector%20Search-green.svg)](https://www.mongodb.com/products/platform/atlas-vector-search)
[![Gemini API](https://img.shields.io/badge/Google-Gemini%20API-blue.svg)](https://ai.google.dev/)

이 프로젝트는 개인의 이력서를 기반으로, 채용 담당자나 다른 사람들이 자연어 질문을 통해 궁금한 점을 물어보고 답변을 받을 수 있는 대화형 AI 챗봇입니다.

단순한 키워드 매칭을 넘어, 질문의 의미와 문맥을 파악하여 이력서 내용에 기반한 깊이 있는 답변을 생성하는 것을 목표로 합니다. 이 모든 과정은 **RAG(Retrieval-Augmented Generation, 검색 증강 생성)** 기술을 중심으로 구현되었습니다.

## ✨ 주요 기능 (Key Features)

*   **지능적인 의도 분류:** 사용자의 질문을 **'잡담', '일반 대화', '이력서 질문'** 세 가지로 분류하여 각기 다른 방식으로 처리합니다.
*   **문맥을 이해하는 RAG:** "지마켓에서 어떤 프로젝트를 했나요?" 와 같은 이력서 관련 질문은 의미 기반 검색을 통해 정확한 정보를 찾아 답변합니다.
*   **정확한 키워드 기반 답변:** "총 경력은?" 등 명확한 질문은 Vector Search 없이 100% 정확한 정보를 제공합니다.
*   **유연한 대화 능력:** 이력서와 무관한 질문("토마토는 몸에 좋아?")이나 이전 대화를 기억해야 하는 질문("내가 누구게?")에 대해서도 일반 AI 어시스턴트처럼 자연스럽게 대화합니다.
*   **대화 흐름 유지:** 간단한 인사("안녕")를 하더라도 이전 대화의 맥락을 잊지 않고 기억하여 자연스러운 대화를 이어갑니다.
*   **실시간 스트리밍 응답:** `WebFlux`를 사용하여 AI가 생성하는 답변을 실시간으로 사용자에게 전달하여 UX를 개선했습니다.

## 🏗️ 아키텍처 및 기술 스택 (Architecture & Tech Stack)

이 서비스는 **'Three-Agent' 아키텍처**를 기반으로 한 RAG 파이프라인을 중심으로 설계되었습니다.

**데이터 흐름:**
`사용자 질문` ➡️ `의도 분류 라우터` ➡️ `(RAG 파이프라인 또는 일반 대화 파이프라인)` ➡️ `Google Gemini API` ➡️ `실시간 답변 스트림`

**기술 스택:**
*   **Backend:** Kotlin, Spring Boot 3
*   **AI / LLM:** Google Gemini API (텍스트 생성 및 임베딩)
*   **Database:** MongoDB Atlas (Vector Search 기능 활용)
*   **Asynchronous:** Spring WebFlux / Project Reactor (스트리밍 응답 처리)
*   **Libraries:** Jackson (JSON 처리), Spring Data MongoDB

## ⚙️ 동작 원리 (How It Works)

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

## 🚀 시작하기 (Getting Started)

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
```이제 애플리케이션이 실행되고, 시작 로그에 Chunk 생성 및 인덱싱 과정이 출력됩니다.

## 🔮 향후 개선 방향 (Future Improvements)

*   **LLM 기반 라우터 도입:** 현재 키워드 기반인 의도 분류기를 더 정교한 LLM 기반 라우터로 교체하여 정확도 향상.
*   **동적 필터 추출 고도화:** 사용자 질문에서 필터링 키워드를 더 지능적으로 추출하는 로직 개선.
*   **UI/Frontend 연동:** 실제 채팅 UI를 구현하여 서비스 완성.
*   **평가 프레임워크 도입:** 답변의 품질을 객관적으로 측정하고, 변경 사항이 성능에 미치는 영향을 추적하는 시스템 구축.