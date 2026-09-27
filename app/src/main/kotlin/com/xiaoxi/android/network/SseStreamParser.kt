package com.xiaoxi.android.network

import com.xiaoxi.android.common.logging.XiaoxiLog
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

                // 2026-09-27 审计 P3 修复：旧判断 `!= "null"`（字符串比较）对非空值恒真，
                // 导致每个 chunk 都多发一次 Done 事件；实际语义应为「finish_reason 非空即流结束」
                if (!choice.finishReason.isNullOrBlank()) {
                    events.add(LlmStreamEvent.Done(choice.finishReason))
                }
            }

            events
        } catch (e: Exception) {
            // 不再静默丢弃：无法解析的载荷要在日志可见，否则用户只会看到"空回复"，无从排查
            XiaoxiLog.w("SseStreamParser", "丢弃无法解析的 SSE 载荷: \"$dataPayload\"", e)
            emptyList()
        }
    }

    fun parseEvents(fullPayload: String): List<LlmStreamEvent> {
        return fullPayload.lineSequence()
            .flatMap { parseLine(it).asSequence() }
            .toList()
    }
}
