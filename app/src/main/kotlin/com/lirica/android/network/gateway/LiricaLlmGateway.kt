package com.lirica.android.network.gateway

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.network.LlmApiException
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmProvider
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.network.model.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 统一多协议大模型网关（M3.1 核心交付物）。
 *
 * 核心职能：
 * 1. 协议路由：依据 LlmConfig 动态派发至 OpenAiProtocolAdapter 或 AnthropicProtocolAdapter；
 * 2. 状态映射：将 HTTP 401（未授权）、402（欠费）、429（超频）等底层报错翻译为清晰中文诊断；
 * 3. 容错重试：流式首字到达前的传输层断线指数退避重试，保障真机移动网络切换下的高韧性；
 * 4. 统一事件流：下游消费方零感知底层厂商差异，统一接收 LlmStreamEvent。
 */
class LiricaLlmGateway(
    private val httpClient: HttpClient = LiricaLlmClient.createDefaultHttpClient(),
    private val openAiAdapter: OpenAiProtocolAdapter = OpenAiProtocolAdapter(),
    private val anthropicAdapter: AnthropicProtocolAdapter = AnthropicProtocolAdapter()
) {

    companion object {
        private const val TAG = "LiricaLlmGateway"
        private const val MAX_STREAM_RETRIES = 2
        private const val RETRY_BACKOFF_BASE_MS = 1000L
    }

    /**
     * 根据配置判定模型通信协议与对应适配器
     */
    fun resolveAdapter(config: LlmConfig): LlmProtocolAdapter {
        return when {
            config.provider == LlmProvider.ANTHROPIC -> anthropicAdapter
            config.baseUrl.contains("anthropic", ignoreCase = true) -> anthropicAdapter
            else -> openAiAdapter
        }
    }

    /**
     * 将 HTTP 状态码与原始错误响应翻译为对用户友好的中文诊断说明
     */
    fun formatErrorMessage(statusCode: Int, rawBody: String): String {
        return when (statusCode) {
            401 -> "API Key 无效或未授权 (HTTP 401 Unauthorized)，请检查设置中的密钥是否正确: $rawBody"
            402 -> "模型服务账户余额不足或已欠费 (HTTP 402 Payment Required)，请前往服务商充值: $rawBody"
            404 -> "模型端点或模型名称不存在 (HTTP 404 Not Found)，请检查 Base URL 与模型名称: $rawBody"
            429 -> "模型请求频次超限或当前并发已满 (HTTP 429 Too Many Requests)，请稍后重试: $rawBody"
            500, 502, 503, 504 -> "模型服务提供商内部异常 (HTTP $statusCode): $rawBody"
            else -> "网络请求失败 (HTTP $statusCode): $rawBody"
        }
    }

    /**
     * 流式调用统一入口
     */
    fun streamChat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): Flow<LlmStreamEvent> = flow {
        if (config.apiKey.isBlank()) {
            emit(LlmStreamEvent.Error(IllegalStateException("API Key 未配置，无法发起模型请求")))
            return@flow
        }

        val adapter = resolveAdapter(config)
        val endpointUrl = adapter.resolveEndpoint(config.baseUrl)
        val headers = adapter.buildHeaders(config.apiKey)
        val requestBody = adapter.buildRequestBody(config, messages, tools)

        var attempt = 0
        var receivedAnyContent = false

        while (true) {
            try {
                val statement = httpClient.preparePost(endpointUrl) {
                    contentType(ContentType.Application.Json)
                    for ((k, v) in headers) {
                        header(k, v)
                    }
                    setBody(requestBody)
                }

                statement.execute { response ->
                    if (response.status != HttpStatusCode.OK) {
                        val errorBody = response.bodyAsText()
                        val diagnosticMsg = formatErrorMessage(response.status.value, errorBody)
                        LiricaLog.e(TAG, "模型调用失败 [${response.status.value}]: $diagnosticMsg")
                        emit(LlmStreamEvent.Error(LlmApiException(response.status, diagnosticMsg)))
                        return@execute
                    }

                    val channel = response.bodyAsChannel()
                    var hasEmittedDone = false
                    var capturedFinishReason: String? = null

                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        val events = adapter.parseStreamLine(line)
                        for (event in events) {
                            if (event is LlmStreamEvent.Token || event is LlmStreamEvent.ToolCallChunk) {
                                receivedAnyContent = true
                            }
                            if (event is LlmStreamEvent.Done) {
                                if (event.finishReason != null) {
                                    capturedFinishReason = event.finishReason
                                }
                                if (!hasEmittedDone) {
                                    hasEmittedDone = true
                                    emit(LlmStreamEvent.Done(event.finishReason ?: capturedFinishReason))
                                }
                            } else {
                                emit(event)
                            }
                        }
                    }

                    if (!hasEmittedDone) {
                        emit(LlmStreamEvent.Done(capturedFinishReason))
                    }
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (receivedAnyContent || attempt >= MAX_STREAM_RETRIES) {
                    emit(LlmStreamEvent.Error(e))
                    return@flow
                }
                attempt++
                val backoffMs = RETRY_BACKOFF_BASE_MS shl (attempt - 1)
                LiricaLog.w(TAG, "SSE 传输中断（未收到内容），${backoffMs}ms 后第 $attempt 次重试: $e")
                delay(backoffMs)
            } catch (e: Throwable) {
                emit(LlmStreamEvent.Error(e))
                return@flow
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * 单轮聚合对话
     */
    suspend fun chat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): String {
        val stringBuilder = StringBuilder()
        var caughtError: Throwable? = null
        streamChat(config, messages, tools).collect { event ->
            when (event) {
                is LlmStreamEvent.Token -> stringBuilder.append(event.text)
                is LlmStreamEvent.Error -> caughtError = event.throwable
                else -> {}
            }
        }
        if (caughtError != null) {
            throw caughtError!!
        }
        return stringBuilder.toString()
    }
}
