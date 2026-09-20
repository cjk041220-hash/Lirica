package com.xiaoxi.android.network

import com.xiaoxi.android.network.model.ChatCompletionRequest
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.network.model.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LlmApiException(val statusCode: HttpStatusCode, override val message: String) : RuntimeException(message)

class XiaoxiLlmClient(
    private val httpClient: HttpClient = createDefaultHttpClient()
) {

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        /** 流式传输层失败时的最大重试次数（仅限未收到任何 Token 的场景） */
        private const val MAX_STREAM_RETRIES = 2

        /** 重试退避基数，指数递增：1s、2s */
        private const val RETRY_BACKOFF_BASE_MS = 1000L

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(OkHttp) {
                engine {
                    config {
                        // SSE 长连接超时策略：
                        // - connectTimeout 15s：建连上限
                        // - readTimeout 60s：字节块最大间隔，覆盖推理模型"长思考不吐字"场景（OkHttp 默认 10s 会掐断流）
                        // - callTimeout 0：整体调用不设上限，由流式读取自然结束
                        connectTimeout(15, TimeUnit.SECONDS)
                        readTimeout(60, TimeUnit.SECONDS)
                        writeTimeout(30, TimeUnit.SECONDS)
                        callTimeout(0, TimeUnit.MILLISECONDS)
                    }
                }
                install(ContentNegotiation) {
                    json(defaultJson)
                }
            }
        }
    }

    fun streamChat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): Flow<LlmStreamEvent> = flow {
        val sanitized = LlmParamSanitizer.sanitize(config)
        val endpointUrl = if (sanitized.baseUrl.endsWith("/chat/completions")) {
            sanitized.baseUrl
        } else {
            "${sanitized.baseUrl}/chat/completions"
        }

        val requestPayload = ChatCompletionRequest(
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

        // 指数退避重试：仅对"尚未向下游发出任何 Token/工具分片"的传输层失败生效，
        // 避免流中途重试导致内容重复下发。
        var attempt = 0
        var receivedAnyContent = false
        while (true) {
            try {
                val statement = httpClient.preparePost(endpointUrl) {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Accept, "text/event-stream")
                    header(HttpHeaders.Authorization, "Bearer ${sanitized.apiKey}")
                    setBody(defaultJson.encodeToString(requestPayload))
                }

                statement.execute { response ->
                    if (response.status != HttpStatusCode.OK) {
                        val errorBody = response.bodyAsText()
                        val errorMessage = "HTTP ${response.status.value} ${response.status.description}: $errorBody"
                        emit(LlmStreamEvent.Error(LlmApiException(response.status, errorMessage)))
                        return@execute
                    }

                    val channel = response.bodyAsChannel()
                    var hasEmittedDone = false
                    var capturedFinishReason: String? = null

                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        val events = SseStreamParser.parseLine(line)
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
                // 结构化并发：调用方取消必须原样向上传播，不得包装成业务错误
                throw e
            } catch (e: IOException) {
                if (receivedAnyContent || attempt >= MAX_STREAM_RETRIES) {
                    emit(LlmStreamEvent.Error(e))
                    return@flow
                }
                attempt++
                val backoffMs = RETRY_BACKOFF_BASE_MS shl (attempt - 1)
                println("[XiaoxiLlmClient] SSE 传输中断（尚未收到内容），${backoffMs}ms 后进行第 $attempt 次重试: $e")
                delay(backoffMs)
            } catch (e: Throwable) {
                emit(LlmStreamEvent.Error(e))
                return@flow
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * 便捷同步/挂起方法：聚合流式 Token 并返回完整回复文本
     * 用于自纠重试、念头生成与单轮快速对话
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
