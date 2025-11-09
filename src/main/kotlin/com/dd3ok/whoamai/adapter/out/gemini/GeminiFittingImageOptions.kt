package com.dd3ok.whoamai.adapter.out.gemini

import org.springframework.ai.image.ImageOptions

/**
 * 작업 목적: 의류 교체 시나리오에 필요한 원본 이미지 바이트와 옵션 값을 Spring AI ImageModel 옵션으로 전달한다.
 * 주요 로직: Google GenAI 이미지 API 호출에 필요한 person/clothing 이미지와 MIME 타입, 모델 옵션을 보관한다.
 */
data class GeminiFittingImageOptions(
    private val personImageSource: ByteArray,
    private val clothingImageSource: ByteArray,
    val mimeType: String,
    private val modelOverride: String?,
    val temperature: Float?,
    val seed: Int?
) : ImageOptions {

    private val personImageData = personImageSource.copyOf()
    private val clothingImageData = clothingImageSource.copyOf()

    init {
        require(personImageData.isNotEmpty()) { "personImageData must not be empty." }
        require(clothingImageData.isNotEmpty()) { "clothingImageData must not be empty." }
    }

    val personImage: ByteArray
        get() = personImageData.copyOf()

    val clothingImage: ByteArray
        get() = clothingImageData.copyOf()

    override fun getN(): Int? = 1
    override fun getModel(): String? = modelOverride
    override fun getWidth(): Int? = null
    override fun getHeight(): Int? = null
    override fun getResponseFormat(): String? = null
    override fun getStyle(): String? = null
}
