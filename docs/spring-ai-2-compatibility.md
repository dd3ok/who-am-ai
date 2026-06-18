# Spring AI 2 Compatibility Notes

Date: 2026-06-18

This project currently stays on Spring AI `1.1.6`. Do not combine a Spring AI `2.x` upgrade with career-agent behavior changes; the upgrade should be a separate branch.

## Current Runtime Artifacts

Verified with:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.9+10'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; .\gradlew.bat dependencyInsight --dependency spring-ai --configuration runtimeClasspath
```

Runtime Spring AI artifacts are resolved through `org.springframework.ai:spring-ai-bom:1.1.6`, including:

- `spring-ai-autoconfigure-model-chat-client:1.1.6`
- `spring-ai-autoconfigure-model-chat-memory:1.1.6`
- `spring-ai-autoconfigure-model-chat-observation:1.1.6`
- `spring-ai-autoconfigure-model-google-genai:1.1.6`
- `spring-ai-autoconfigure-model-tool:1.1.6`
- `spring-ai-autoconfigure-vector-store-mongodb-atlas:1.1.6`
- `spring-ai-client-chat:1.1.6`
- `spring-ai-commons:1.1.6`
- `spring-ai-google-genai:1.1.6`
- `spring-ai-google-genai-embedding:1.1.6`
- `spring-ai-model:1.1.6`
- `spring-ai-mongodb-atlas-store:1.1.6`
- `spring-ai-retry:1.1.6`
- `spring-ai-starter-model-google-genai:1.1.6`
- `spring-ai-starter-model-google-genai-embedding:1.1.6`
- `spring-ai-starter-vector-store-mongodb-atlas:1.1.6`
- `spring-ai-template-st:1.1.6`
- `spring-ai-vector-store:1.1.6`

## Migration Hotspots

- Advisor naming differs: this codebase targets Spring AI `1.1.6`, where local jars contain `ToolCallAdvisor`; newer documentation uses `ToolCallingAdvisor`.
- Tool-calling behavior changed around advisor auto-registration in newer Spring AI versions. Recheck whether `.tools(...)` automatically registers/exercises a tool advisor and whether any `spring.ai.chat.client.tool-calling.*` properties are required.
- Memory and tool-calling advisor ordering must be revalidated. Keep memory outside the tool-call loop unless repository support for tool-call messages is proven.
- Check whether the final Spring AI `2.x` dependency graph couples to Spring Boot `4.x`; this repository is currently on Spring Boot `3.5.14`.
- Recheck Google GenAI option names and model property binding before changing the BOM.
- Confirm MongoDB Atlas Vector Store package/artifact names before upgrading; vector-store advisor artifacts were renamed in Spring AI upgrade notes.

## Recommended Upgrade Shape

1. Create a dedicated compatibility branch.
2. Upgrade only Spring AI dependencies and required package imports.
3. Run adapter, RAG retrieval, router, WebSocket, and full suite tests before changing agent behavior.
4. Re-run live resume QA only as an opt-in check with `MONGO_URI` and `GEMINI_API_KEY`.

No dependency upgrade is performed in this task.
