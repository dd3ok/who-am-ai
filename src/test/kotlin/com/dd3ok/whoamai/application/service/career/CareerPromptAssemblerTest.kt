package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.application.port.out.ResumeSearchResult
import com.dd3ok.whoamai.common.service.PromptProvider
import com.dd3ok.whoamai.domain.ChatMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CareerPromptAssemblerTest {

    private val assembler = CareerPromptAssembler(FakePromptProvider)

    @Test
    fun `rag plan appends rendered rag prompt with joined contexts`() {
        val input = CareerPromptInput(
            history = listOf(ChatMessage(role = "user", text = "이전 질문")),
            userPrompt = "최근에는 뭐하나요?",
            contexts = listOf("context-1", "context-2"),
            useRagPrompt = true
        )

        val messages = assembler.assemble(input)

        assertEquals("이전 질문", messages.first().text)
        assertEquals("RAG::최근에는 뭐하나요?::context-1\n---\ncontext-2", messages.last().text)
    }

    @Test
    fun `rag plan with empty contexts uses grounded empty marker`() {
        val input = CareerPromptInput(
            history = emptyList(),
            userPrompt = "백엔드 강점은?",
            contexts = emptyList(),
            useRagPrompt = true
        )

        val messages = assembler.assemble(input)

        assertEquals("RAG::백엔드 강점은?::관련 정보 없음", messages.single().text)
    }

    @Test
    fun `non rag plan appends rendered conversational prompt`() {
        val input = CareerPromptInput(
            history = emptyList(),
            userPrompt = "안녕하세요",
            contexts = emptyList(),
            useRagPrompt = false
        )

        val messages = assembler.assemble(input)

        assertEquals("CHAT::안녕하세요", messages.single().text)
    }

    @Test
    fun `rag plan includes source link when url is present and omits it otherwise`() {
        val input = CareerPromptInput(
            history = emptyList(),
            userPrompt = "개인 프로젝트 알려줘",
            contexts = emptyList(),
            useRagPrompt = true,
            sources = listOf(
                ResumeSearchResult(
                    chunkId = "project_lucid",
                    chunkType = "project",
                    content = "컨텍스트 점검 도구",
                    title = "lucid",
                    url = "https://github.com/dd3ok/lucid"
                ),
                ResumeSearchResult(
                    chunkId = "project_esm",
                    chunkType = "project",
                    content = "셀러딜 개선",
                    title = "ESM 셀러딜",
                    url = null
                )
            )
        )

        val messages = assembler.assemble(input)

        assertEquals(
            "RAG::개인 프로젝트 알려줘::[자료: lucid / project_lucid / 링크: https://github.com/dd3ok/lucid]\n컨텍스트 점검 도구\n---\n[자료: ESM 셀러딜 / project_esm]\n셀러딜 개선",
            messages.single().text
        )
    }

    private object FakePromptProvider : PromptProvider {
        override fun systemInstruction(): String = ""
        override fun routingInstruction(): String = ""
        override fun renderRoutingTemplate(
            resumeOwnerName: String,
            companies: List<String>,
            skills: List<String>,
            question: String
        ): String = question

        override fun renderRagTemplate(context: String, question: String): String = "RAG::$question::$context"

        override fun renderConversationalTemplate(question: String): String = "CHAT::$question"
    }
}
