package com.xiaoxi.android.network

import app.cash.turbine.test
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmProvider
import com.xiaoxi.android.network.model.LlmStreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    @Test
    fun testDeepSeekSanitizationRemovesZeroTopPAndSetsDefaults() {
        val rawConfig = LlmConfig(
            apiKey = "sk-test-key",
            provider = LlmProvider.DEEPSEEK,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            topP = 0.0,
            temperature = null,
            presencePenalty = null
        )

        val sanitized = LlmParamSanitizer.sanitize(rawConfig)

        // 验证绝对移除了 top_p=0.0 陷阱
        assertNull("DeepSeek top_p <= 0 必须被清洗为 null，杜绝 400 报错", sanitized.topP)
        // 验证默认注入 0.95 与 0.25 维持鲜活度
        assertEquals(0.95, sanitized.temperature ?: 0.0, 0.001)
        assertEquals(0.25, sanitized.presencePenalty ?: 0.0, 0.001)
    }

    @Test
    fun testDeepSeekSanitizationPreservesValidTopP() {
        val rawConfig = LlmConfig(
            apiKey = "sk-test-key",
            provider = LlmProvider.DEEPSEEK,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            topP = 0.85,
            temperature = 0.7,
            presencePenalty = 0.1
        )

        val sanitized = LlmParamSanitizer.sanitize(rawConfig)

        assertEquals(0.85, sanitized.topP ?: 0.0, 0.001)
        assertEquals(0.7, sanitized.temperature ?: 0.0, 0.001)
        assertEquals(0.1, sanitized.presencePenalty ?: 0.0, 0.001)
    }

    @Test
    fun testOpenAiProviderDoesNotEnforceDeepSeekDefaults() {
        val rawConfig = LlmConfig(
            apiKey = "sk-openai",
            provider = LlmProvider.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o",
            topP = 0.0,
            temperature = null,
            presencePenalty = null
        )

        val sanitized = LlmParamSanitizer.sanitize(rawConfig)

        // 非 DeepSeek 不强制覆盖默认 temperature 和 presencePenalty
        assertEquals(0.0, sanitized.topP ?: -1.0, 0.001)
        assertNull(sanitized.temperature)
        assertNull(sanitized.presencePenalty)
    }

    @Test
    fun testSseStreamParserEmitsTokensAndDone() = runTest {
        val ssePayload = """
            data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","content":"Lirica"},"finish_reason":null}]}

            data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{"content":"已连接"},"finish_reason":null}]}

            data: {"id":"chatcmpl-3","choices":[{"index":0,"delta":{"content":"。"},"finish_reason":null}]}

            data: [DONE]
        """.trimIndent()

        val events = SseStreamParser.parseEvents(ssePayload)

        assertEquals(4, events.size)
        assertTrue(events[0] is LlmStreamEvent.Token && (events[0] as LlmStreamEvent.Token).text == "Lirica")
        assertTrue(events[1] is LlmStreamEvent.Token && (events[1] as LlmStreamEvent.Token).text == "已连接")
        assertTrue(events[2] is LlmStreamEvent.Token && (events[2] as LlmStreamEvent.Token).text == "。")
        assertTrue(events[3] is LlmStreamEvent.Done)
    }

    @Test
    fun testSseStreamParserIncrementalToolCalls() = runTest {
        val ssePayload = """
            data: {"id":"chatcmpl-tool-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_01","type":"function","function":{"name":"open_app","arguments":""}}]},"finish_reason":null}]}

            data: {"id":"chatcmpl-tool-2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"package\":"}}]},"finish_reason":null}]}

            data: {"id":"chatcmpl-tool-3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"com.tencent.mm\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]
        """.trimIndent()

        val events = SseStreamParser.parseEvents(ssePayload)

        val toolCallChunks = events.filterIsInstance<LlmStreamEvent.ToolCallChunk>()
        assertEquals(3, toolCallChunks.size)

        val assembler = ToolCallAssembler()
        toolCallChunks.forEach { assembler.appendChunk(it) }
        val completedCalls = assembler.getCompletedToolCalls()

        assertEquals(1, completedCalls.size)
        val toolCall = completedCalls[0]
        assertEquals("call_01", toolCall.id)
        assertEquals("open_app", toolCall.name)
        assertEquals("{\"package\":\"com.tencent.mm\"}", toolCall.arguments)
    }

    @Test
    fun testKtorClientMockStreamingWithDeepSeekSanitization() = runTest {
        var recordedRequestBody: String? = null
        var recordedAuthHeader: String? = null

        val mockEngine = MockEngine { request ->
            recordedRequestBody = request.body.toByteArray().decodeToString()
            recordedAuthHeader = request.headers[HttpHeaders.Authorization]

            val responseData = """
                data: {"id":"1","choices":[{"index":0,"delta":{"content":"你好，世界！"},"finish_reason":"stop"}]}

                data: [DONE]
            """.trimIndent()

            respond(
                content = responseData,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(this@LlmClientTest.json)
            }
        }

        val client = XiaoxiLlmClient(httpClient = httpClient)
        val config = LlmConfig(
            apiKey = "sk-deepseek-secret",
            provider = LlmProvider.DEEPSEEK,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            topP = 0.0, // 故意传入 0.0 陷阱
            temperature = null
        )

        val flow = client.streamChat(
            config = config,
            messages = listOf(ChatMessage(role = "user", content = "Hi"))
        )

        flow.test {
            val event1 = awaitItem()
            assertTrue(event1 is LlmStreamEvent.Token)
            assertEquals("你好，世界！", (event1 as LlmStreamEvent.Token).text)

            val event2 = awaitItem()
            assertTrue(event2 is LlmStreamEvent.Done)

            awaitComplete()
        }

        assertEquals("Bearer sk-deepseek-secret", recordedAuthHeader)
        assertNotNull(recordedRequestBody)
        // 关键断言：发送的 HTTP 请求体中绝不能包含 "top_p":0
        assertFalse("请求体中严禁包含 top_p: 0", recordedRequestBody!!.contains("\"top_p\":0"))
        assertTrue("请求体中必须包含默认温度 0.95", recordedRequestBody!!.contains("\"temperature\":0.95"))
    }

    @Test
    fun testKtorClientHandles401Unauthorized() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """{"error":{"message":"Invalid API key provided."}}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(this@LlmClientTest.json)
            }
        }

        val client = XiaoxiLlmClient(httpClient = httpClient)
        val config = LlmConfig(
            apiKey = "sk-invalid",
            provider = LlmProvider.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o"
        )

        val flow = client.streamChat(
            config = config,
            messages = listOf(ChatMessage(role = "user", content = "Test"))
        )

        flow.test {
            val errorEvent = awaitItem()
            assertTrue("必须捕获并转换为 Error 事件", errorEvent is LlmStreamEvent.Error)
            val err = (errorEvent as LlmStreamEvent.Error).throwable
            assertTrue("错误信息中应包含 401 或 Unauthorized", err.message?.contains("401") == true || err.message?.contains("Unauthorized") == true)
            awaitComplete()
        }
    }

    @Test
    fun testChatConvenienceMethodReturnsAggregatedText() = runTest {
        val mockEngine = MockEngine { _ ->
            val responseData = """
                data: {"id":"1","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

                data: {"id":"2","choices":[{"index":0,"delta":{"content":"，Lirica！"},"finish_reason":"stop"}]}

                data: [DONE]
            """.trimIndent()

            respond(
                content = responseData,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(this@LlmClientTest.json)
            }
        }

        val client = XiaoxiLlmClient(httpClient = httpClient)
        val config = LlmConfig(
            apiKey = "sk-test",
            baseUrl = "https://api.deepseek.com/v1"
        )

        val fullText = client.chat(
            config = config,
            messages = listOf(ChatMessage(role = "user", content = "Hi"))
        )

        assertEquals("你好，Lirica！", fullText)
    }
}
