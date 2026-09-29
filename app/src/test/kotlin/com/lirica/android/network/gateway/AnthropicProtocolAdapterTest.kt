package com.lirica.android.network.gateway

import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmProvider
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.network.model.ToolDefinition
import com.lirica.android.network.model.ToolFunction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicProtocolAdapterTest {

    private lateinit var adapter: AnthropicProtocolAdapter

    @Before
    fun setUp() {
        adapter = AnthropicProtocolAdapter()
    }

    @Test
    fun testResolveEndpoint_handlesBaseUrl() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            adapter.resolveEndpoint("https://api.anthropic.com")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            adapter.resolveEndpoint("https://api.anthropic.com/v1/messages")
        )
    }

    @Test
    fun testBuildHeaders_containsAnthropicHeaders() {
        val headers = adapter.buildHeaders("sk-ant-test-key")
        assertEquals("sk-ant-test-key", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun testBuildRequestBody_extractsSystemPromptAndMapsTools() {
        val config = LlmConfig(
            apiKey = "sk-ant-123",
            provider = LlmProvider.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            model = "claude-3-5-sonnet-20241022",
            maxTokens = 2048
        )
        val messages = listOf(
            ChatMessage(role = "system", content = "你是 Lirica，一个活人感伴生智能体。"),
            ChatMessage(role = "user", content = "你好呀")
        )
        val tools = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "read_screen",
                    description = "识读屏幕文字",
                    parameters = buildJsonObject {
                        put("type", "object")
                    }
                )
            )
        )

        val json = adapter.buildRequestBody(config, messages, tools)
        assertTrue("系统提示词必须提取为顶层 system 字段", json.contains("\"system\":\"你是 Lirica，一个活人感伴生智能体。\""))
        assertTrue("messages 数组不能包含 system 角色", !json.contains("\"role\":\"system\""))
        assertTrue("messages 数组包含 user 消息", json.contains("\"role\":\"user\""))
        assertTrue("工具必须转换为 input_schema", json.contains("\"input_schema\""))
        assertTrue("必须声明 stream", json.contains("\"stream\":true"))
        assertTrue("必须设置 max_tokens", json.contains("\"max_tokens\":2048"))
    }

    @Test
    fun testParseStreamLine_textDelta() {
        val line = """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"主人，我在呢。"}}"""
        val events = adapter.parseStreamLine(line)
        assertEquals(1, events.size)
        assertTrue(events[0] is LlmStreamEvent.Token)
        assertEquals("主人，我在呢。", (events[0] as LlmStreamEvent.Token).text)
    }

    @Test
    fun testParseStreamLine_toolUseStartAndDelta() {
        // 1. content_block_start with tool_use
        val startLine = """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}"""
        val startEvents = adapter.parseStreamLine(startLine)
        assertEquals(1, startEvents.size)
        val startChunk = startEvents[0] as LlmStreamEvent.ToolCallChunk
        assertEquals("toolu_01", startChunk.id)
        assertEquals("get_weather", startChunk.name)
        assertEquals(1, startChunk.index)

        // 2. content_block_delta with input_json_delta
        val deltaLine = """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Beijing\"}"}}"""
        val deltaEvents = adapter.parseStreamLine(deltaLine)
        assertEquals(1, deltaEvents.size)
        val deltaChunk = deltaEvents[0] as LlmStreamEvent.ToolCallChunk
        assertEquals("{\"city\":\"Beijing\"}", deltaChunk.argumentsDelta)
        assertEquals(1, deltaChunk.index)
    }

    @Test
    fun testParseStreamLine_messageStop() {
        val stopLine = """data: {"type":"message_stop"}"""
        val events = adapter.parseStreamLine(stopLine)
        assertEquals(1, events.size)
        assertTrue(events[0] is LlmStreamEvent.Done)
    }
}
