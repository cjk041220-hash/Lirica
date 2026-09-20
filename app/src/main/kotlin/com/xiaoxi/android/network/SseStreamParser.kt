package com.xiaoxi.android.network

import com.xiaoxi.android.network.model.ChatCompletionChunk
import com.xiaoxi.android.network.model.LlmStreamEvent
import kotlinx.serialization.json.Json

object SseStreamParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseLine(line: String): List<LlmStreamEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:") || trimmed.startsWith("id:")) {
            return emptyList()
        }

        if (!trimmed.startsWith("data:")) {
            return emptyList()
        }

        val dataPayload = trimmed.removePrefix("data:").trim()
        if (dataPayload == "[DONE]") {
            return listOf(LlmStreamEvent.Done())
        }

        return try {
            val chunk = json.decodeFromString<ChatCompletionChunk>(dataPayload)
            val events = mutableListOf<LlmStreamEvent>()

            for (choice in chunk.choices) {
                val content = choice.delta.content
                if (!content.isNullOrEmpty()) {
                    events.add(LlmStreamEvent.Token(content))
                }

                choice.delta.toolCalls?.forEach { toolCall ->
                    events.add(
                        LlmStreamEvent.ToolCallChunk(
                            index = toolCall.index,
                            id = toolCall.id,
                            name = toolCall.function?.name,
                            argumentsDelta = toolCall.function?.arguments ?: ""
                        )
                    )
                }

                if (choice.finishReason != null && choice.finishReason != "null") {
                    events.add(LlmStreamEvent.Done(choice.finishReason))
                }
            }

            events
        } catch (e: Exception) {
            // 不再静默丢弃：无法解析的载荷要在 Logcat 可见（tag: System.out），否则用户只会看到"空回复"，无从排查
            println("[SseStreamParser] 丢弃无法解析的 SSE 载荷: \"$dataPayload\" (error: $e)")
            emptyList()
        }
    }

    fun parseEvents(fullPayload: String): List<LlmStreamEvent> {
        return fullPayload.lineSequence()
            .flatMap { parseLine(it).asSequence() }
            .toList()
    }
}
