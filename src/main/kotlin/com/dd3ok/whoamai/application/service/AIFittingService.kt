package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.adapter.out.gemini.GeminiAdapter
import com.dd3ok.whoamai.application.port.`in`.AIFittingUseCase
import com.google.genai.types.Part
import org.springframework.stereotype.Service

@Service
class AIFittingService(
    private val geminiAdapter: GeminiAdapter
) : AIFittingUseCase {
    override suspend fun generateStyledImage(
        personImageFile: ByteArray,
        clothingImageFile: ByteArray
    ): ByteArray {
        try {
            val personImagePart = bytesToPart(personImageFile, "image/jpeg")
            val clothingImagePart = bytesToPart(clothingImageFile, "image/jpeg")

            val response = geminiAdapter.generateImageContent(clothingImagePart, personImagePart)

            val candidates = response.candidates().get()
            if (candidates.isEmpty()) {
                throw Exception("API로부터 유효한 응답을 받지 못했습니다.")
            }

            val contentParts = candidates.first().content()?.get()?.parts()?.get().orEmpty()

            for (part in contentParts) {
                val blob = part.inlineData().orElse(null)
                if (blob != null) {
                    val data = blob.data().orElse(null)
                    if (data != null && data.isNotEmpty()) {
                        return data
                    }
                }
            }
            throw Exception("API로부터 유효한 이미지 응답을 받지 못했습니다.")
        } catch (e: Exception) {
            println("Gemini API 호출 중 오류 발생: ${e.message}")
            throw Exception("이미지 생성에 실패했습니다: ${e.message}", e)
        }
    }

    // ByteArray → Part 유틸 (MultipartFile 미사용)
    private fun bytesToPart(bytes: ByteArray, mimeType: String = "image/jpeg"): Part {
        require(bytes.isNotEmpty()) { "빈 바이트 배열입니다." }
        require(mimeType.isNotBlank()) { "MIME 타입이 비어 있습니다." }
        return Part.fromBytes(bytes, mimeType)
    }
}