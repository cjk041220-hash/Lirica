package com.xiaoxi.android.automation.agent

import com.xiaoxi.android.automation.coordinator.CoordinatorAction
import com.xiaoxi.android.automation.coordinator.HelixGuiCoordinator
import com.xiaoxi.android.automation.coordinator.SwipeDirection
import com.xiaoxi.android.automation.coordinator.TaskExecutionResult
import com.xiaoxi.android.automation.grounding.GroundingQuery
import com.xiaoxi.android.automation.intent.DispatchResult
import com.xiaoxi.android.automation.intent.IntentAction
import com.xiaoxi.android.automation.intent.IntentActionDispatcher
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.ToolCall
import com.xiaoxi.android.network.model.ToolDefinition
import com.xiaoxi.android.network.model.ToolFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ToolExecutionResult(
    val toolCallId: String,
    val isSuccess: Boolean,
    val output: String,
    val errorMessage: String? = null
) {
    fun toChatMessage(): ChatMessage {
        return ChatMessage(
            role = "tool",
            toolCallId = toolCallId,
            content = output
        )
    }
}

class NativeToolAgent(
    private val coordinator: HelixGuiCoordinator,
    private val intentDispatcher: IntentActionDispatcher,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    val declaredTools: List<ToolDefinition> by lazy {
        listOf(
            ToolDefinition(
                type = "function",
                function = ToolFunction(
                    name = "open_app",
                    description = "打开指定的手机应用程序或系统设置",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("app_name") {
                                put("type", "string")
                                put("description", "要打开的应用名称，例如'高德地图'、'微信'、'设置'")
                            }
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "应用的完整包名（可选）")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("app_name"))
                        }
                    }
                )
            ),
            ToolDefinition(
                type = "function",
                function = ToolFunction(
                    name = "click_element",
                    description = "在当前手机屏幕上根据文本或控件ID定位并点击指定元素",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "屏幕上要点击的按钮或元素文本")
                            }
                            putJsonObject("resource_id") {
                                put("type", "string")
                                put("description", "要点击的控件资源ID（可选）")
                            }
                            putJsonObject("description") {
                                put("type", "string")
                                put("description", "操作目的简述")
                            }
                        }
                    }
                )
            ),
            ToolDefinition(
                type = "function",
                function = ToolFunction(
                    name = "input_text",
                    description = "在当前聚焦或指定的输入框中键入文本",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "要在输入框中键入的文本内容")
                            }
                            putJsonObject("target_text") {
                                put("type", "string")
                                put("description", "目标输入框的提示文本或现有文本")
                            }
                            putJsonObject("target_resource_id") {
                                put("type", "string")
                                put("description", "目标输入框的控件资源ID")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("text"))
                        }
                    }
                )
            ),
            ToolDefinition(
                type = "function",
                function = ToolFunction(
                    name = "swipe",
                    description = "在当前手机屏幕上执行滑动操作",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("direction") {
                                put("type", "string")
                                put("description", "滑动方向：UP, DOWN, LEFT, RIGHT")
                            }
                            putJsonObject("distance") {
                                put("type", "string")
                                put("description", "滑动幅度：short, medium, long")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("direction"))
                        }
                    }
                )
            ),
            ToolDefinition(
                type = "function",
                function = ToolFunction(
                    name = "task_finish",
                    description = "告知任务执行完成并提供最终结论摘要",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("summary") {
                                put("type", "string")
                                put("description", "任务执行完成的总结陈述")
                            }
                            putJsonObject("success") {
                                put("type", "boolean")
                                put("description", "任务是否成功达成目标")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("summary"))
                        }
                    }
                )
            )
        )
    }

    suspend fun executeToolCall(call: ToolCall): ToolExecutionResult {
        val args = runCatching {
            json.parseToJsonElement(call.arguments) as? JsonObject
        }.getOrNull()

        if (args == null) {
            return ToolExecutionResult(
                toolCallId = call.id,
                isSuccess = false,
                output = "参数解析失败: 无法将 arguments 解析为合法的 JSON 对象"
            )
        }

        return when (call.name) {
            "open_app" -> {
                val appName = args["app_name"]?.jsonPrimitive?.content ?: ""
                val packageName = args["package_name"]?.jsonPrimitive?.content ?: ""
                val action = IntentAction.LaunchApp(packageName = packageName.ifBlank { appName })
                when (val result = intentDispatcher.dispatch(action)) {
                    is DispatchResult.Success -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = "应用 [$appName] 已成功打开并置于前台"
                        )
                    }
                    else -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "打开应用 [$appName] 失败: $result"
                        )
                    }
                }
            }

            "click_element" -> {
                val text = args["text"]?.jsonPrimitive?.content
                val resourceId = args["resource_id"]?.jsonPrimitive?.content
                val desc = args["description"]?.jsonPrimitive?.content ?: "点击元素 $text"

                val action = CoordinatorAction.Click(
                    query = GroundingQuery(text = text, resourceId = resourceId),
                    description = desc
                )

                when (val result = coordinator.executeTask("点击元素", listOf(action))) {
                    is TaskExecutionResult.Success -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = "点击操作完成: ${result.message}"
                        )
                    }
                    is TaskExecutionResult.Failure -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "点击操作失败: ${result.reason}"
                        )
                    }
                    TaskExecutionResult.Cancelled -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "点击操作被取消"
                        )
                    }
                }
            }

            "input_text" -> {
                val text = args["text"]?.jsonPrimitive?.content ?: ""
                val targetText = args["target_text"]?.jsonPrimitive?.content
                val targetResourceId = args["target_resource_id"]?.jsonPrimitive?.content

                val action = CoordinatorAction.Type(
                    query = GroundingQuery(text = targetText, resourceId = targetResourceId),
                    text = text,
                    description = "键入文本 $text"
                )

                when (val result = coordinator.executeTask("输入文本", listOf(action))) {
                    is TaskExecutionResult.Success -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = "输入操作完成: ${result.message}"
                        )
                    }
                    is TaskExecutionResult.Failure -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "输入操作失败: ${result.reason}"
                        )
                    }
                    TaskExecutionResult.Cancelled -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "输入操作被取消"
                        )
                    }
                }
            }

            "swipe" -> {
                val dirStr = args["direction"]?.jsonPrimitive?.content?.uppercase() ?: "UP"
                val direction = runCatching { SwipeDirection.valueOf(dirStr) }.getOrDefault(SwipeDirection.UP)
                val action = CoordinatorAction.Swipe(
                    direction = direction,
                    humanoid = true,
                    description = "滑动屏幕: $direction"
                )

                when (val result = coordinator.executeTask("滑动操作", listOf(action))) {
                    is TaskExecutionResult.Success -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = "滑动操作完成"
                        )
                    }
                    else -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "滑动操作失败: $result"
                        )
                    }
                }
            }

            "task_finish" -> {
                val summary = args["summary"]?.jsonPrimitive?.content ?: "任务完成"
                val success = args["success"]?.jsonPrimitive?.booleanOrNull ?: true
                ToolExecutionResult(
                    toolCallId = call.id,
                    isSuccess = success,
                    output = summary
                )
            }

            else -> {
                ToolExecutionResult(
                    toolCallId = call.id,
                    isSuccess = false,
                    output = "未知的系统工具指令: [${call.name}]"
                )
            }
        }
    }

    suspend fun executeToolCalls(calls: List<ToolCall>): List<ToolExecutionResult> {
        return calls.map { executeToolCall(it) }
    }
}
