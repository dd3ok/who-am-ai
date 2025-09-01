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

            val prompt = """
            목표는 왼쪽 의류 사진의 옷을 오른쪽 인물 사진의 모델에게 입히는 것입니다.
            - 왼쪽 의류 사진에 인물이 포함되면 해당 인물이 입은 옷을 추출해주세요.
            - 왼쪽 의류 사진에 상의, 하의 둘다 있다면 둘다 추출해 주세요.
            - 오른쪽 인물사진의 기존 옷을 제거하고 왼쪽 의류 사진에서 추출된 옷으로 자연스럽게 입혀주세요.
        """.trimIndent()

            val response = geminiAdapter.generateImageContent(
                parts = listOf(clothingImagePart, personImagePart, Part.fromText(prompt)),
                systemInstruction = "결과물은 text가 아닌 image만 생성함"
            )

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