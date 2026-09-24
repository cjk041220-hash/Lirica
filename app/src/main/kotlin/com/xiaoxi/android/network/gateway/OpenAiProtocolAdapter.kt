package com.xiaoxi.android.network.gateway

import com.xiaoxi.android.network.LlmParamSanitizer
import com.xiaoxi.android.network.SseStreamParser
import com.xiaoxi.android.network.model.ChatCompletionRequest
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.network.model.ToolDefinition
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
        }
    }

    override val protocol: LlmProtocol = LlmProtocol.OPENAI

    override fun resolveEndpoint(baseUrl: String): String {
        val normalized = baseUrl.trim().removeSuffix("/")
        return if (normalized.endsWith("/chat/completions")) {
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
