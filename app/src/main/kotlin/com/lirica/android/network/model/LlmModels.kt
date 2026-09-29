package com.lirica.android.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LlmProvider {
    @SerialName("deepseek")
    DEEPSEEK,

    @SerialName("openai")
    OPENAI,

    @SerialName("anthropic")
    ANTHROPIC,

    @SerialName("custom")
    CUSTOM
}

data class LlmConfig(
    val apiKey: String,
    val provider: LlmProvider = LlmProvider.DEEPSEEK,
    val baseUrl: String = "https://api.deepseek.com/v1",
    val model: String = "deepseek-chat",
    val temperature: Double? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val maxTokens: Int? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: kotlinx.serialization.json.JsonObject? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
) {
    val name: String get() = function.name
    val arguments: String get() = function.arguments
}

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Double? = null,
    @SerialName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val tools: List<ToolDefinition>? = null
)

@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChunkUsage? = null
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCallChunkData>? = null
)

@Serializable
data class ToolCallChunkData(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallChunk? = null
)

@Serializable
data class FunctionCallChunk(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class ChunkUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0
)

sealed interface LlmStreamEvent {
    data class Token(val text: String) : LlmStreamEvent
    data class ToolCallChunk(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String
    ) : LlmStreamEvent
    data class Done(val finishReason: String? = null) : LlmStreamEvent
    data class Error(val throwable: Throwable) : LlmStreamEvent
}
