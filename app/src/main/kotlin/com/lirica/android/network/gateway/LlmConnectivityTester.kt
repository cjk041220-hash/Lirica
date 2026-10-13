package com.lirica.android.network.gateway

import com.lirica.android.network.LlmApiException
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig

sealed interface ConnectivityResult {
    data class Success(
        val latencyMs: Long,
        val model: String,
        val responseSnippet: String
    ) : ConnectivityResult

    data class Failure(
        val statusCode: Int?,
        val latencyMs: Long,
        val diagnosticMessage: String,
        val rawError: String
    ) : ConnectivityResult
}

/**
 * 大模型连通性握手探测器（M3.1 T1.7 核心实现）。
 *
 * 特性：
 * 1. 单 Token 极速探针：限制 maxTokens = 1 发起问候探测，毫秒级测出网络 RTT 与 TTFT，极大降低测试成本；
 * 2. 精确错误归因：对 401（未授权）、402（欠费）、429（超频）返回清晰中文诊断，不闪退；
 * 3. 安全防护：测试过程中不打印敏感 API Key，只返回延迟、模型名与响应摘要。
 */
class LlmConnectivityTester(
    private val gateway: LiricaLlmGateway = LiricaLlmGateway()
) {

    suspend fun testConnection(config: LlmConfig): ConnectivityResult {
        if (config.apiKey.isBlank()) {
            return ConnectivityResult.Failure(
                statusCode = null,
                latencyMs = 0,
                diagnosticMessage = "API Key 为空，请先输入有效的密钥",
                rawError = "Missing API Key"
            )
        }

        val start = System.currentTimeMillis()
        return try {
            val probeMessage = listOf(ChatMessage(role = "user", content = "hi"))
            // 探针配置：单 token 极速响应
            val probeConfig = config.copy(maxTokens = 1)
            val reply = gateway.chat(probeConfig, probeMessage)
            val latency = System.currentTimeMillis() - start

            ConnectivityResult.Success(
                latencyMs = latency,
                model = config.model,
                responseSnippet = reply.take(60)
            )
        } catch (e: LlmApiException) {
            val latency = System.currentTimeMillis() - start
            ConnectivityResult.Failure(
                statusCode = e.statusCode.value,
                latencyMs = latency,
                diagnosticMessage = e.message ?: "模型调用失败",
                rawError = e.message ?: ""
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M6.0 P1-7：连通性探测作为可取消操作，取消必须上抛而非谎报"网络失败"
            throw e
        } catch (e: Throwable) {
            val latency = System.currentTimeMillis() - start
            ConnectivityResult.Failure(
                statusCode = null,
                latencyMs = latency,
                diagnosticMessage = "网络连接失败: ${e.message ?: "未知异常"}",
                rawError = e.message ?: e.toString()
            )
        }
    }
}
