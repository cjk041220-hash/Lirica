package com.xiaoxi.android.network.gateway

import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.network.model.ToolDefinition

/**
 * 大模型通信协议适配器契约。
 * 屏蔽不同模型服务商（OpenAI 兼容规范 vs Anthropic Messages 规范）在
 * URL 端点、请求头鉴权、JSON 负载装配与流式 SSE 事件分片上的异构细节。
 */
interface LlmProtocolAdapter {
    val protocol: LlmProtocol

    /**
     * 计算服务商真实网络端点 URL
     */
    fun resolveEndpoint(baseUrl: String): String

    /**
     * 构建鉴权与专用 HTTP 请求头
     */
    fun buildHeaders(apiKey: String): Map<String, String>

    /**
     * 将标准会话与工具契约序列化为协议专有请求体 JSON
     */
    fun buildRequestBody(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?
    ): String

    /**
     * 解析协议专有的一行 SSE 文本，转换为标准流式领域事件
     */
    fun parseStreamLine(line: String): List<LlmStreamEvent>
}
