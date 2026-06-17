package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.AIFittingUseCase
import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.file.Path

class AIFittingControllerTest {

    @Test
    fun `file larger than header limit returns payload too large`() = runTest {
        val useCase = RecordingAIFittingUseCase()
        val controller = AIFittingController(useCase, GeminiImageModelProperties())

        val response = controller.generateStyledImage(
            personImageFile = filePart(contentLength = MAX_BYTES + 1),
            clothingImageFile = filePart()
        )

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.statusCode)
        assertFalse(useCase.wasCalled)
    }

    @Test
    fun `data buffer limit failure returns payload too large`() = runTest {
        val useCase = RecordingAIFittingUseCase()
        val controller = AIFittingController(useCase, GeminiImageModelProperties())

        val response = controller.generateStyledImage(
            personImageFile = filePart(content = Flux.error(DataBufferLimitException("too large"))),
            clothingImageFile = filePart()
        )

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.statusCode)
        assertFalse(useCase.wasCalled)
    }

    private class RecordingAIFittingUseCase : AIFittingUseCase {
        var wasCalled = false

        override suspend fun generateStyledImage(personImageFile: ByteArray, clothingImageFile: ByteArray): ByteArray {
            wasCalled = true
            return byteArrayOf(1)
        }
    }

    private class StubFilePart(
        private val partName: String,
        private val headers: HttpHeaders,
        private val content: Flux<DataBuffer>
    ) : FilePart {
        override fun name(): String = partName
        override fun filename(): String = "$partName.png"
        override fun headers(): HttpHeaders = headers
        override fun content(): Flux<DataBuffer> = content
        override fun transferTo(dest: Path): Mono<Void> = Mono.error(UnsupportedOperationException())
    }

    private companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024L
        private val bufferFactory = DefaultDataBufferFactory()

        private fun filePart(
            name: String = "image",
            contentType: MediaType = MediaType.IMAGE_PNG,
            contentLength: Long? = 1,
            content: Flux<DataBuffer> = Flux.just(bufferFactory.wrap(byteArrayOf(1)))
        ): FilePart {
            val headers = HttpHeaders().apply {
                this.contentType = contentType
                if (contentLength != null) {
                    this.contentLength = contentLength
                }
            }
            return StubFilePart(name, headers, content.doOnDiscard(DataBuffer::class.java, DataBufferUtils::release))
        }
    }
}
