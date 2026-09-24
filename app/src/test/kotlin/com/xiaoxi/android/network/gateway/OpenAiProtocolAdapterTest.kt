package com.xiaoxi.android.network.gateway

import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmProvider
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.network.model.ToolDefinition
import com.xiaoxi.android.network.model.ToolFunction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiProtocolAdapterTest {

    private lateinit var adapter: OpenAiProtocolAdapter

    @Before
    fun setUp() {
        adapter = OpenAiProtocolAdapter()
    }

    @Test
    fun testResolveEndpoint_handlesTrailingChatCompletions() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            adapter.resolveEndpoint("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            adapter.resolveEndpoint("https://api.deepseek.com/v1/chat/completions")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            adapter.resolveEndpoint("https://api.openai.com/v1/")
        )
    }

    @Test
    fun testBuildHeaders_containsBearerTokenAndAcceptSse() {
        val headers = adapter.buildHeaders("sk-test-token-123")
        assertEquals("Bearer sk-test-token-123", headers["Authorization"])
        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun testBuildRequestBody_includesToolsAndMessages() {
        val config = LlmConfig(
            apiKey = "sk-123",
            provider = LlmProvider.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o"
        )
        val messages = listOf(
            ChatMessage(role = "user", content = "今天北京天气如何？")
        )
        val tools = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "get_weather",
                    description = "获取城市天气",
                    parameters = buildJsonObject {
                        put("type", "object")
                    }
                )
            )
        )

        val bodyJson = adapter.buildRequestBody(config, messages, tools)
        assertTrue(bodyJson.contains("\"model\":\"gpt-4o\""))
        assertTrue(bodyJson.contains("\"stream\":true"))
        assertTrue(bodyJson.contains("\"get_weather\""))
        assertTrue(bodyJson.contains("今天北京天气如何？"))
    }

    @Test
    fun testParseStreamLine_providerDeepSeek() {
        val line = """data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":"Lirica在"},"finish_reason":null}]}"""
        val events = adapter.parseStreamLine(line)
        assertEquals(1, events.size)
        assertTrue(events[0] is LlmStreamEvent.Token)
        assertEquals("Lirica在", (events[0] as LlmStreamEvent.Token).text)
    }

    @Test
    fun testParseStreamLine_providerSiliconFlowQwen() {
        val line = """data: {"id":"sf-888","choices":[{"index":0,"delta":{"role":"assistant","content":"听着呢"},"finish_reason":null}]}"""
        val events = adapter.parseStreamLine(line)
        assertEquals(1, events.size)
        assertEquals("听着呢", (events[0] as LlmStreamEvent.Token).text)
    }

    @Test
    fun testParseStreamLine_providerMoonshotToolCall() {
        val line = """data: {"id":"ms-01","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"read_screen","arguments":"{}"}}]},"finish_reason":null}]}"""
        val events = adapter.parseStreamLine(line)
        assertEquals(1, events.size)
        assertTrue(events[0] is LlmStreamEvent.ToolCallChunk)
        val chunk = events[0] as LlmStreamEvent.ToolCallChunk
        assertEquals(0, chunk.index)
        assertEquals("call_abc", chunk.id)
        assertEquals("read_screen", chunk.name)
    }

    @Test
    fun testParseStreamLine_doneSignal() {
        val line = "data: [DONE]"
        val events = adapter.parseStreamLine(line)
        assertEquals(1, events.size)
        assertTrue(events[0] is LlmStreamEvent.Done)
    }
}
