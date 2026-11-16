package com.dd3ok.whoamai.application.service.career

import com.dd3ok.whoamai.common.config.ChatCacheProperties
import com.dd3ok.whoamai.domain.CachedChatAnswer
import com.dd3ok.whoamai.domain.ChatEvent
import com.dd3ok.whoamai.domain.ChatEventType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class CachedAnswerStreamPacer(
    private val properties: ChatCacheProperties = ChatCacheProperties(),
    private val sleep: suspend (Long) -> Unit,
    private val randomLong: (Long, Long) -> Long
) {
    @Autowired
    constructor(properties: ChatCacheProperties) : this(
        properties = properties,
        sleep = { millis -> if (millis > 0) delay(millis) },
        randomLong = { min, max ->
            val safeMin = min.coerceAtLeast(0L)
            val safeMax = max.coerceAtLeast(safeMin)
            if (safeMin == safeMax) safeMin else Random.nextLong(safeMin, safeMax + 1)
        }
    )

    fun streamEvents(cached: CachedChatAnswer): Flow<ChatEvent> = flow {
        emit(ChatEvent(ChatEventType.SOURCES, sources = cached.sources))

        val initialDelay = randomLong(properties.initialDelayMinMs, properties.initialDelayMaxMs)
        sleep(initialDelay)

        val chunks = splitIntoNaturalChunks(cached.answerText)
        for ((index, chunk) in chunks.withIndex()) {
            emit(ChatEvent(ChatEventType.DELTA, content = chunk))
            if (index < chunks.lastIndex) {
                val delayMs = if (chunk.endsWith("\n") || chunk.endsWith(". ") || chunk.endsWith(":\n")) {
                    properties.pauseDelayMs.coerceAtLeast(0L)
                } else {
                    randomLong(properties.chunkDelayMinMs, properties.chunkDelayMaxMs)
                }
                sleep(delayMs)
            }
        }
    }

    internal fun splitIntoNaturalChunks(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val avgChunkDelay = ((properties.chunkDelayMinMs + properties.chunkDelayMaxMs) / 2.0).coerceAtLeast(1.0)
        val estimatedSteps = (properties.targetStreamDurationMs / avgChunkDelay).toInt().coerceIn(10, 65)
        val approxChunkSize = ((text.length + estimatedSteps - 1) / estimatedSteps).coerceAtLeast(4)

        val chunks = mutableListOf<String>()
        var cursor = 0
        while (cursor < text.length) {
            var end = (cursor + approxChunkSize).coerceAtMost(text.length)
            if (end < text.length) {
                val searchWindowEnd = (end + 6).coerceAtMost(text.length)
                var boundary = -1
                for (i in end until searchWindowEnd) {
                    val ch = text[i - 1]
                    if (ch == '\n' || ch == ' ') {
                        boundary = i
                        break
                    }
                }
                if (boundary != -1) {
                    end = boundary
                } else if (Character.isHighSurrogate(text[end - 1]) && end < text.length) {
                    end += 1
                }
            }
            chunks += text.substring(cursor, end)
            cursor = end
        }
        return chunks
    }
}
