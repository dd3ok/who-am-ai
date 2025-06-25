package com.dd3ok.whoamai.infrastructure.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.ChatUseCase
import com.dd3ok.whoamai.domain.StreamMessage
import com.dd3ok.whoamai.domain.MessageType
import kotlinx.coroutines.flow.toList
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/test")
class TestController(
    // 웹소켓 핸들러와 동일하게, Application Service(Use Case)를 주입받습니다.
    private val chatUseCase: ChatUseCase
) {

    // 요청 본문을 받기 위한 간단한 데이터 클래스
    data class TestRequest(val prompt: String)

    /**
     * Gemini 연동을 테스트하기 위한 간단한 POST 엔드포인트.
     * 스트리밍이 아닌, 전체 응답을 한 번에 반환합니다.
     */
    @PostMapping("/gemini")
    suspend fun testGemini(@RequestBody request: TestRequest): ResponseEntity<String> {
        // 1. UseCase를 호출하여 응답 스트림(Flow)을 받습니다.
        val responseStream = chatUseCase.streamChatResponse(
            // ChatUseCase는 ChatMessage를 인자로 받으므로, 임의의 메시지를 만들어 전달합니다.
            StreamMessage(
                uuid = "1234",
                type = MessageType.USER,
                content = request.prompt
            )
        )

        // 2. 스트림(Flow)의 모든 조각들을 하나로 합쳐서 완전한 문자열로 만듭니다.
        val fullResponse = responseStream.toList().joinToString("")

        // 3. 완성된 전체 텍스트를 HTTP 응답으로 반환합니다.
        return ResponseEntity.ok(fullResponse)
    }
}
