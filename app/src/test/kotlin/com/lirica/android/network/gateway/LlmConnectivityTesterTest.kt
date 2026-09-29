package com.lirica.android.network.gateway

import com.lirica.android.network.LlmApiException
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmProvider
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmConnectivityTesterTest {

    private val mockGateway = mockk<LiricaLlmGateway>()
    private lateinit var tester: LlmConnectivityTester

    @Before
    fun setUp() {
        tester = LlmConnectivityTester(gateway = mockGateway)
    }

    @Test
    fun testConnection_success() = runTest {
        val config = LlmConfig(apiKey = "sk-valid", baseUrl = "https://api.deepseek.com/v1", model = "deepseek-chat")
        coEvery { mockGateway.chat(any(), any(), any()) } returns "Hello"

        val result = tester.testConnection(config)
        assertTrue(result is ConnectivityResult.Success)
        val success = result as ConnectivityResult.Success
        assertEquals("deepseek-chat", success.model)
        assertEquals("Hello", success.responseSnippet)
        assertTrue(success.latencyMs >= 0)
    }

    @Test
    fun testConnection_unauthorized401_returnsDiagnosticFailure() = runTest {
        val config = LlmConfig(apiKey = "sk-invalid", baseUrl = "https://api.openai.com/v1", model = "gpt-4o")
        val err = LlmApiException(HttpStatusCode.Unauthorized, "API Key 无效或未授权 (HTTP 401 Unauthorized)")
        coEvery { mockGateway.chat(any(), any(), any()) } throws err

        val result = tester.testConnection(config)
        assertTrue(result is ConnectivityResult.Failure)
        val failure = result as ConnectivityResult.Failure
        assertEquals(401, failure.statusCode)
        assertTrue(failure.diagnosticMessage.contains("401"))
        assertTrue(failure.diagnosticMessage.contains("API Key 无效或未授权"))
    }

    @Test
    fun testConnection_insufficientQuota402_returnsDiagnosticFailure() = runTest {
        val config = LlmConfig(apiKey = "sk-no-balance", baseUrl = "https://api.custom.com/v1", model = "qwen")
        val err = LlmApiException(HttpStatusCode.PaymentRequired, "余额不足或已欠费 (HTTP 402 Payment Required)")
        coEvery { mockGateway.chat(any(), any(), any()) } throws err

        val result = tester.testConnection(config)
        assertTrue(result is ConnectivityResult.Failure)
        val failure = result as ConnectivityResult.Failure
        assertEquals(402, failure.statusCode)
        assertTrue(failure.diagnosticMessage.contains("余额不足"))
    }

    @Test
    fun testConnection_networkError_returnsFailure() = runTest {
        val config = LlmConfig(apiKey = "sk-valid", baseUrl = "https://invalid-domain-xxxx.com", model = "test")
        coEvery { mockGateway.chat(any(), any(), any()) } throws java.net.UnknownHostException("invalid-domain-xxxx.com")

        val result = tester.testConnection(config)
        assertTrue(result is ConnectivityResult.Failure)
        val failure = result as ConnectivityResult.Failure
        assertEquals(null, failure.statusCode)
        assertTrue(failure.diagnosticMessage.contains("网络连接失败"))
    }
}
