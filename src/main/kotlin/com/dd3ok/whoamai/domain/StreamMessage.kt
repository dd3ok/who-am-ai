package com.dd3ok.whoamai.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class StreamMessage @JsonCreator constructor(
    @param:JsonProperty("uuid") val uuid: String,
    @param:JsonProperty("type") val type: MessageType,
    @param:JsonProperty("content") val content: String,
)
