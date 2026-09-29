package com.lirica.android.network.gateway

import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiricaLlmGatewayTest {

    private lateinit var gateway: LiricaLlmGateway

    @Before
    fun setUp() {
        gateway = LiricaLlmGateway()
    }

    @Test
    fun testSelectAdapter_openaiCompatible() {
        val deepseekConfig = LlmConfig(apiKey = "key", provider = LlmProvider.DEEPSEEK, baseUrl = "https://api.deepseek.com/v1")
        val adapter1 = gateway.resolveAdapter(deepseekConfig)
        assertEquals(LlmProtocol.OPENAI, adapter1.protocol)

        val openaiConfig = LlmConfig(apiKey = "key", provider = LlmProvider.OPENAI, baseUrl = "https://api.openai.com/v1")
        val adapter2 = gateway.resolveAdapter(openaiConfig)
        assertEquals(LlmProtocol.OPENAI, adapter2.protocol)

        val customOpenAiConfig = LlmConfig(apiKey = "key", provider = LlmProvider.CUSTOM, baseUrl = "https://api.siliconflow.cn/v1")
        val adapter3 = gateway.resolveAdapter(customOpenAiConfig)
        assertEquals(LlmProtocol.OPENAI, adapter3.protocol)
    }

    @Test
    fun testSelectAdapter_anthropic() {
        val anthropicConfig = LlmConfig(apiKey = "key", provider = LlmProvider.ANTHROPIC, baseUrl = "https://api.anthropic.com")
        val adapter1 = gateway.resolveAdapter(anthropicConfig)
        assertEquals(LlmProtocol.ANTHROPIC, adapter1.protocol)

        val customAnthropicUrl = LlmConfig(apiKey = "key", provider = LlmProvider.CUSTOM, baseUrl = "https://api.anthropic.com/v1")
        val adapter2 = gateway.resolveAdapter(customAnthropicUrl)
        assertEquals(LlmProtocol.ANTHROPIC, adapter2.protocol)
    }

    @Test
    fun testFormatErrorMessage_translatesCommonHttpStatuses() {
        val msg401 = gateway.formatErrorMessage(401, "Unauthorized")
        assertTrue("401 必须给出明确的 API Key 无效提示", msg401.contains("API Key 无效或未授权"))

        val msg402 = gateway.formatErrorMessage(402, "Payment Required")
        assertTrue("402 必须给出余额不足提示", msg402.contains("余额不足或已欠费"))

        val msg429 = gateway.formatErrorMessage(429, "Too Many Requests")
        assertTrue("429 必须给出频次超限提示", msg429.contains("请求频次超限"))

        val msg500 = gateway.formatErrorMessage(500, "Internal Server Error")
        assertTrue("500 给出服务端异常提示", msg500.contains("模型服务提供商内部异常"))
    }
}
