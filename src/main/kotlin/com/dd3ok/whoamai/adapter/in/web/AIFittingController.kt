package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.AIFittingUseCase
import com.dd3ok.whoamai.common.config.GeminiImageModelProperties
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.util.MimeTypeUtils
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-fitting")
class AIFittingController(
    private val aiFittingUseCase: AIFittingUseCase,
    private val imageModelProperties: GeminiImageModelProperties
) {

    private val allowedMediaTypes = setOf(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG)
    private val maxBytes = 5 * 1024 * 1024 // 5MB 안전 제한

    @PostMapping(consumes = [MULTIPART_FORM_DATA_VALUE])
    suspend fun generateStyledImage(
        @RequestPart("personImage") personImageFile: FilePart,
        @RequestPart("clothingImage") clothingImageFile: FilePart
    ): ResponseEntity<ByteArray> {
        return try {
            validateFilePart("personImage", personImageFile)
            validateFilePart("clothingImage", clothingImageFile)

            val personBytes = readBytes(personImageFile)
            val clothingBytes = readBytes(clothingImageFile)

            if (personBytes.size > maxBytes || clothingBytes.size > maxBytes) {
                ResponseEntity.status(413).build()
            } else {
                val resultImage = aiFittingUseCase.generateStyledImage(personBytes, clothingBytes)

                val resolvedMediaType = imageModelProperties.mimeType
                    .takeIf { it.isNotBlank() }
                    ?.let(MediaType::parseMediaType)
                    ?: MediaType.IMAGE_JPEG

                ResponseEntity.ok()
                    .contentType(resolvedMediaType)
                    .body(resultImage)
            }
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        } catch (e: Exception) {
            ResponseEntity.status(500).build()
        }
    }

    private suspend fun readBytes(filePart: FilePart): ByteArray {
        val dataBuffer = DataBufferUtils.join(filePart.content()).awaitSingle()
        val bytes = ByteArray(dataBuffer.readableByteCount())
        dataBuffer.read(bytes)
        DataBufferUtils.release(dataBuffer)
        return bytes
    }

    private fun validateFilePart(name: String, filePart: FilePart) {
        val contentType = filePart.headers().contentType
        val isAllowedType = contentType?.let { allowedMediaTypes.any { allowed -> allowed.isCompatibleWith(it) } } ?: false
        if (!isAllowedType) {
            throw IllegalArgumentException("$name: 지원되지 않는 이미지 형식입니다. (${contentType ?: MimeTypeUtils.ALL_VALUE})")
        }

        val contentLength = filePart.headers().contentLength
        if (contentLength > 0 && contentLength > maxBytes) {
            throw IllegalArgumentException("$name: 파일 크기가 허용 한도(5MB)를 초과했습니다.")
        }
    }
}
