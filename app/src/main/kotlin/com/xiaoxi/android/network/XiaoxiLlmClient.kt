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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(OkHttp) {
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
                var lastFinishReason: String? = null

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val events = SseStreamParser.parseLine(line)
                    for (event in events) {
                        if (event is LlmStreamEvent.Done) {
                            if (!hasEmittedDone) {
                                hasEmittedDone = true
                                emit(LlmStreamEvent.Done(event.finishReason ?: lastFinishReason))
                            }
                        } else {
                            emit(event)
                        }
                    }
                }
                if (!hasEmittedDone) {
                    emit(LlmStreamEvent.Done(lastFinishReason))
                }
            }
        } catch (e: Throwable) {
            emit(LlmStreamEvent.Error(e))
        }
    }
}
