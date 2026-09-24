package com.xiaoxi.android.network.gateway

import com.xiaoxi.android.network.LlmParamSanitizer
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.network.model.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Anthropic Claude Messages API 协议适配器。
 * 适配 Claude 原生 /v1/messages 端点、x-api-key 鉴权头、
 * 顶层 system 分流与 content_block_delta 流式分块。
 */
class AnthropicProtocolAdapter(
    private val json: Json = defaultJson
) : LlmProtocolAdapter {

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    override val protocol: LlmProtocol = LlmProtocol.ANTHROPIC

    private var lastStopReason: String? = null

    override fun resolveEndpoint(baseUrl: String): String {
        val normalized = baseUrl.trim().removeSuffix("/")
        return when {
            normalized.endsWith("/v1/messages") || normalized.endsWith("/messages") -> normalized
            normalized.endsWith("/v1") -> "$normalized/messages"
            else -> "$normalized/v1/messages"
        }
    }

    override fun buildHeaders(apiKey: String): Map<String, String> {
        return mapOf(
            "x-api-key" to apiKey.trim(),
            "anthropic-version" to ANTHROPIC_VERSION,
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

        // Anthropic 要求 system 提示词置于顶层，不能放入 messages 数组
        val systemMessages = messages.filter { it.role.equals("system", ignoreCase = true) }
        val systemPrompt = if (systemMessages.isNotEmpty()) {
            systemMessages.mapNotNull { it.content }.joinToString("\n\n")
        } else null

        val nonSystemMessages = messages.filterNot { it.role.equals("system", ignoreCase = true) }

        val rootObject = buildJsonObject {
            put("model", sanitized.model)
            put("stream", true)
            put("max_tokens", sanitized.maxTokens ?: DEFAULT_MAX_TOKENS)

            if (!systemPrompt.isNullOrBlank()) {
                put("system", systemPrompt)
            }

            if (sanitized.temperature != null) {
                put("temperature", sanitized.temperature)
            }

            if (sanitized.topP != null) {
                put("top_p", sanitized.topP)
            }

            // 转换会话消息
            put("messages", buildJsonArray {
                for (msg in nonSystemMessages) {
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content ?: "")
                    })
                }
            })

            // 转换工具契约
            if (!tools.isNullOrEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("name", tool.function.name)
                            put("description", tool.function.description ?: "")
                            put("input_schema", tool.function.parameters ?: buildJsonObject {
                                put("type", "object")
                            })
                        })
                    }
                })
            }
        }

        return json.encodeToString(JsonObject.serializer(), rootObject)
    }

    override fun parseStreamLine(line: String): List<LlmStreamEvent> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return emptyList()

        val dataContent = trimmed.removePrefix("data:").trim()
        if (dataContent.isEmpty()) return emptyList()

        val events = mutableListOf<LlmStreamEvent>()
        runCatching {
            val root = json.parseToJsonElement(dataContent).jsonObject
            val type = root["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "content_block_delta" -> {
                    val delta = root["delta"]?.jsonObject
                    val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull
                    val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull
                            if (!text.isNullOrEmpty()) {
                                events.add(LlmStreamEvent.Token(text))
                            }
                        }
                        "input_json_delta" -> {
                            val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                            events.add(
                                LlmStreamEvent.ToolCallChunk(
                                    index = index,
                                    id = null,
                                    name = null,
                                    argumentsDelta = partialJson
                                )
                            )
                        }
                    }
                }
                "content_block_start" -> {
                    val block = root["content_block"]?.jsonObject
                    val blockType = block?.get("type")?.jsonPrimitive?.contentOrNull
                    val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0

                    if (blockType == "tool_use") {
                        val id = block["id"]?.jsonPrimitive?.contentOrNull
                        val name = block["name"]?.jsonPrimitive?.contentOrNull
                        events.add(
                            LlmStreamEvent.ToolCallChunk(
                                index = index,
                                id = id,
                                name = name,
                                argumentsDelta = ""
                            )
                        )
                    }
                }
                "message_delta" -> {
                    val delta = root["delta"]?.jsonObject
                    val stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                    if (stopReason != null) {
                        lastStopReason = stopReason
                    }
                }
                "message_stop" -> {
                    events.add(LlmStreamEvent.Done(lastStopReason))
                    lastStopReason = null
                }
                "error" -> {
                    val errorObj = root["error"]?.jsonObject
                    val errorMsg = errorObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic API Error"
                    events.add(LlmStreamEvent.Error(RuntimeException(errorMsg)))
                }
            }
        }
        return events
    }
}
