package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.application.port.`in`.AIFittingUseCase
import com.dd3ok.whoamai.application.port.out.GeminiPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AIFittingService(
    private val geminiPort: GeminiPort
) : AIFittingUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun generateStyledImage(
        personImageFile: ByteArray,
        clothingImageFile: ByteArray
    ): ByteArray {
        return try {
            geminiPort.generateStyledImage(personImageFile, clothingImageFile)
        } catch (e: Exception) {
            logger.error("Gemini image generation failed: ${e.message}", e)
            throw Exception("이미지 생성에 실패했습니다: ${e.message}", e)
        }
    }
}
