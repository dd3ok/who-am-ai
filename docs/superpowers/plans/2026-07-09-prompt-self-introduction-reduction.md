# Prompt Self-Introduction Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce repeated model answers such as "커리어 에이전트로서 답변드립니다" while keeping the portfolio UI label "커리어 에이전트" unchanged.

**Architecture:** Keep the existing WebSocket protocol, RAG routing, prompt loading, and Gemini adapter unchanged. Update only prompt resource wording and the prompt resource regression test so backend prompts describe the service purpose without forcing the assistant to self-identify as "커리어 에이전트" in answers.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 3.5.14, JUnit 5, StringTemplate prompt resources.

## Global Constraints

- Do not change the frontend portfolio UI labels or welcome copy.
- Do not change WebSocket payload shape or chat persistence behavior.
- Do not introduce runtime dependencies.
- Keep prompt copy concise and portfolio-focused.
- Avoid instructions that encourage the model to announce its role unless the user explicitly asks what the service is.

---

### Task 1: Remove Forced Prompt Self-Branding

**Files:**
- Modify: `src/test/kotlin/com/dd3ok/whoamai/common/service/PromptBrandingTest.kt`
- Modify: `src/main/resources/prompts/system.st`
- Modify: `src/main/resources/prompts/rag-template.st`
- Modify: `src/main/resources/prompts/conversational-template.st`

**Interfaces:**
- Consumes: `PromptTemplateService` reads `src/main/resources/prompts/*.st` as UTF-8 resources.
- Produces: Prompt resources that still constrain answer tone and factual grounding, but no longer require assistant self-identification as "커리어 에이전트".

- [x] **Step 1: Write the failing test**

Replace the required `currentName` assertion in `PromptBrandingTest` with a guard that rejects forced self-branding in backend prompt resources:

```kotlin
private val forcedSelfBrandingPhrases = listOf(
    "\uCEE4\uB9AC\uC5B4 \uC5D0\uC774\uC804\uD2B8",
    "\uC774\uB825\uC11C \uC5D0\uC774\uC804\uD2B8",
    "\uC778\uC7AC AI"
)

@Test
fun `prompt resources do not force agent self branding`() {
    val promptsPath = Path.of(
        requireNotNull(javaClass.classLoader.getResource("prompts")) {
            "prompts resource directory not found"
        }.toURI()
    )

    val promptTexts = Files.walk(promptsPath).use { paths ->
        paths
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".st") }
            .map { it.fileName.toString() to Files.readString(it) }
            .toList()
    }

    assertTrue(promptTexts.isNotEmpty(), "Expected prompt resources to be present")

    promptTexts.forEach { (fileName, text) ->
        forcedSelfBrandingPhrases.forEach { phrase ->
            assertFalse(text.contains(phrase), "Forced self-branding phrase remains in $fileName: $phrase")
        }
    }
}
```

- [x] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "*PromptBrandingTest"
```

Expected: the test fails because `system.st`, `rag-template.st`, and `conversational-template.st` still contain `커리어 에이전트`.

- [x] **Step 3: Update `system.st`**

Replace:

```text
### 역할
- '커리어 에이전트'로서 친근하고 전문적으로 응답한다.
```

With:

```text
### 역할
- 포트폴리오 방문자의 경력, 프로젝트, 기술 스택 질문에 친근하고 전문적으로 응답한다.
- 사용자가 서비스 정체성을 직접 묻지 않는 한 답변 첫머리에 역할명이나 자기소개 문장을 붙이지 않는다.
```

- [x] **Step 4: Update `rag-template.st`**

Replace:

```text
- 정체성: 시스템 가드 준수(스스로를 '커리어 에이전트'로만 언급).
```

With:

```text
- 정체성: 요청받지 않은 자기소개나 역할명 언급 없이 질문에 바로 답한다.
```

- [x] **Step 5: Update `conversational-template.st`**

Replace:

```text
- 역할: '커리어 에이전트'로 대화 이력을 고려해 최신 사용자 메시지에 답변한다.
```

With:

```text
- 역할: 포트폴리오 Q&A 응답자로서 대화 이력을 고려해 최신 사용자 메시지에 답변한다.
- 자기소개: 사용자가 서비스 정체성을 직접 묻지 않으면 역할명이나 자기소개 문장으로 시작하지 않는다.
```

- [x] **Step 6: Run the focused test and verify it passes**

Run:

```powershell
.\gradlew.bat test --tests "*PromptBrandingTest"
```

Expected: `PromptBrandingTest` passes.

- [x] **Step 7: Run related prompt/template tests**

Run:

```powershell
.\gradlew.bat test --tests "*PromptTemplateServiceTest" --tests "*PromptBrandingTest" --tests "*CareerPromptAssemblerTest"
```

Expected: all selected tests pass.

- [x] **Step 8: Record progress**

Progress is recorded below.

## Progress

- 2026-07-09: Removed forced backend prompt self-branding while keeping UI branding unchanged; focused prompt tests pass with JDK 21.

## Acceptance Criteria

- Backend prompt resources no longer contain `커리어 에이전트`, `이력서 에이전트`, or `인재 AI`.
- The frontend portfolio can still label the chat UI as `커리어 에이전트`; no frontend files are modified by this task.
- RAG prompts still require context grounding and concise answers.
- General chat prompts still avoid unsolicited references to 유인재 or resume details.
- Focused prompt and prompt assembly tests pass.

## Full Verification Command

Run before PR or deployment:

```powershell
.\gradlew.bat test
```

Expected: full test suite passes.

## Self-Review

- Spec coverage: The plan covers removing backend prompt self-branding and preserving frontend UI branding.
- Placeholder scan: No placeholders, TBDs, or open-ended implementation steps remain.
- Scope check: This is a single prompt behavior change with no dependency, protocol, frontend, or persistence changes.
- Type consistency: The only Kotlin test class touched is `PromptBrandingTest`; no production Kotlin APIs change.
