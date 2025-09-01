package com.dd3ok.whoamai.application.port.`in`

interface AIFittingUseCase {
    suspend fun generateStyledImage(personImageFile: ByteArray, clothingImageFile: ByteArray): ByteArray
}