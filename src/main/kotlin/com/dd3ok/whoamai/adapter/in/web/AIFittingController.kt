package com.dd3ok.whoamai.adapter.`in`.web

import com.dd3ok.whoamai.application.port.`in`.AIFittingUseCase
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.MediaType.IMAGE_JPEG_VALUE
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai-fitting")
class AIFittingController(
    private val aiFittingUseCase: AIFittingUseCase
) {

    @PostMapping(consumes = [MULTIPART_FORM_DATA_VALUE], produces = [IMAGE_JPEG_VALUE])
    suspend fun generateStyledImage(
        @RequestPart("personImage") personImageFile: FilePart,
        @RequestPart("clothingImage") clothingImageFile: FilePart
    ): ResponseEntity<ByteArray> = try {
        val personBytesBuf = DataBufferUtils.join(personImageFile.content()).awaitSingle()
        val clothingBytesBuf = DataBufferUtils.join(clothingImageFile.content()).awaitSingle()

        val personBytes = ByteArray(personBytesBuf.readableByteCount())
        personBytesBuf.read(personBytes)
        DataBufferUtils.release(personBytesBuf)

        val clothingBytes = ByteArray(clothingBytesBuf.readableByteCount())
        clothingBytesBuf.read(clothingBytes)
        DataBufferUtils.release(clothingBytesBuf)

        val resultImage = aiFittingUseCase.generateStyledImage(personBytes, clothingBytes)

        val headers = HttpHeaders().apply { contentType = MediaType.IMAGE_JPEG }
        ResponseEntity.ok().headers(headers).body(resultImage)
    } catch (e: Exception) {
        ResponseEntity.status(500).build()
    }
}