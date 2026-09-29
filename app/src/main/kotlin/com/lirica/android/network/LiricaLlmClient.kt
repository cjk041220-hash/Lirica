package com.lirica.android.network

import com.lirica.android.network.gateway.LiricaLlmGateway
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.network.model.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class LlmApiException(val statusCode: HttpStatusCode, override val message: String) : RuntimeException(message)

/**
 * 客户端 LLM 统一门面。
 * 内部将请求委托给底层多协议网关 [LiricaLlmGateway]，统一分流与协议适配。
 */
class LiricaLlmClient(
    val httpClient: HttpClient = createDefaultHttpClient(),
    val gateway: LiricaLlmGateway = LiricaLlmGateway(httpClient)
) {

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(OkHttp) {
                engine {
                    config {
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
    ): Flow<LlmStreamEvent> = gateway.streamChat(config, messages, tools)

    /**
     * 便捷同步/挂起方法：聚合流式 Token 并返回完整回复文本
     * 用于自纠重试、念头生成与单轮快速对话
     */
    suspend fun chat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): String = gateway.chat(config, messages, tools)
}
