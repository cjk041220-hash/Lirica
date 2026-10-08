package com.lirica.android.network.gateway

import com.lirica.android.network.LlmParamSanitizer
import com.lirica.android.network.SseStreamParser
import com.lirica.android.network.model.ChatCompletionRequest
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.network.model.ToolDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OpenAI 兼容协议适配器。
 * 覆盖：DeepSeek、OpenAI 原生、Moonshot (Kimi)、Qwen (通义千问)、SiliconFlow (硅基流动)、OpenRouter 等。
 */
class OpenAiProtocolAdapter(
    private val json: Json = defaultJson
) : LlmProtocolAdapter {

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    override val protocol: LlmProtocol = LlmProtocol.OPENAI

    override fun resolveEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
        val normalized = withScheme.removeSuffix("/")
        return if (normalized.endsWith("/chat/completions", ignoreCase = true)) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    override fun buildHeaders(apiKey: String): Map<String, String> {
        return mapOf(
            "Authorization" to "Bearer ${apiKey.trim()}",
            "Accept" to "text/event-stream",
            "Content-Type" to "application/json"
        )
    }

    override fun buildRequestBody(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?
    ): String {
        val sanitized = LlmParamSanitizer.sanitize(config)
        val request = ChatCompletionRequest(
            model = sanitized.model,
            messages = messages,
            stream = true,
            temperature = sanitized.temperature,
            topP = sanitized.topP,
            presencePenalty = sanitized.presencePenalty,
            frequencyPenalty = sanitized.frequencyPenalty,
            maxTokens = sanitized.maxTokens,
            tools = tools
        )
        return json.encodeToString(request)
    }

    override fun parseStreamLine(line: String): List<LlmStreamEvent> {
        return SseStreamParser.parseLine(line)
    }
}
