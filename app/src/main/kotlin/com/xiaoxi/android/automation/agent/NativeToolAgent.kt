package com.xiaoxi.android.automation.agent

import android.view.KeyEvent
import com.xiaoxi.android.automation.backend.KeycodeMapper
import com.xiaoxi.android.automation.capture.ScreenCaptureManager
import com.xiaoxi.android.automation.coordinator.CoordinatorAction
import com.xiaoxi.android.automation.coordinator.HelixGuiCoordinator
import com.xiaoxi.android.automation.coordinator.SwipeDirection
import com.xiaoxi.android.automation.coordinator.TaskExecutionResult
import com.xiaoxi.android.automation.grounding.GroundingQuery
import com.xiaoxi.android.automation.grounding.VisualElementDetector
import com.xiaoxi.android.automation.intent.DispatchResult
import com.xiaoxi.android.automation.intent.IntentAction
import com.xiaoxi.android.automation.intent.IntentActionDispatcher
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.ToolCall
import com.xiaoxi.android.network.model.ToolDefinition
import com.xiaoxi.android.network.model.ToolFunction
import com.xiaoxi.android.proactivity.ScheduleWakeManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
    private val scheduleWakeManager: ScheduleWakeManager? = null,
    private val screenCaptureManager: ScreenCaptureManager? = null,
    private val visualDetector: VisualElementDetector? = null,
    private val packageResolver: AppPackageResolver = AppPackageResolver(),
    /**
     * 生活工具（天气、日程…）。与设备操控无关的纯网络/纯本地能力，
     * 通过 [LifeTool] 契约挂载，避免 agent 的 when 分支随工具数膨胀。
     * 默认空列表：既有调用方与测试无需改动。
     */
    private val lifeTools: List<LifeTool> = emptyList(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
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
            ),
            ToolDefinition(
                type = "function",
                function = ToolFunction(
                    name = "schedule_wake",
                    description = "Lirica为自己设定一个未来定时提醒闹钟约定，到达指定时间后自动唤醒心智并生成第一人称念头活水",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("delay_minutes") {
                                put("type", "number")
                                put("description", "延迟触发的分钟数（例如 30 表示 30 分钟后）")
                            }
                            putJsonObject("bot_note") {
                                put("type", "string")
                                put("description", "Lirica自己用第一人称记录的定闹钟原因（例如：'半小时后提醒JK喝水'）")
                            }
                            putJsonObject("block_delivery") {
                                put("type", "boolean")
                                put("description", "闹钟到期前是否阻断普通外部事件打扰（可选，默认 false）")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("delay_minutes"))
                            add(JsonPrimitive("bot_note"))
                        }
                    }
                )
            ),
            ToolDefinition(
                type = "function",
                function = ToolFunction(
                    name = "execute_macro",
                    description = "连续执行一组预定义的原子动作列表（动作分块 Action Chunking），单轮指令下发多步点击、输入、滑动、按键或延迟，大幅削减交互往返时延",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("description") {
                                put("type", "string")
                                put("description", "本次宏操作的目标总结")
                            }
                            putJsonObject("actions") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("type") {
                                            put("type", "string")
                                            put("description", "动作类型：click / type / press_key / swipe / delay")
                                        }
                                        putJsonObject("target_text") {
                                            put("type", "string")
                                            put("description", "目标元素文本或无障碍描述（click / type 选用）")
                                        }
                                        putJsonObject("target_resource_id") {
                                            put("type", "string")
                                            put("description", "目标元素 resource-id")
                                        }
                                        putJsonObject("text") {
                                            put("type", "string")
                                            put("description", "待输入的文字内容（type 必填）")
                                        }
                                        putJsonObject("key") {
                                            put("type", "string")
                                            put("description", "按键名称（如 'ENTER', 'BACK', 'HOME', 'SEARCH'）或数字键码（press_key 必填）")
                                        }
                                        putJsonObject("direction") {
                                            put("type", "string")
                                            put("description", "滑动方向：up / down / left / right（swipe 必填）")
                                        }
                                        putJsonObject("delay_ms") {
                                            put("type", "integer")
                                            put("description", "延时等待毫秒数（delay 必填）")
                                        }
                                    }
                                    putJsonArray("required") {
                                        add(JsonPrimitive("type"))
                                    }
                                }
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("actions"))
                        }
                    }
                )
            )
        ) + (if (screenCaptureManager != null) {
            listOf(
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "read_screen",
                        description = "捕获当前手机屏幕并通过端侧 OCR 识别其上所有可见文字内容与布局（适用于无障碍树无法读取的自绘界面、小程序、游戏或需要核对画面文字时）",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("focus_area") {
                                    put("type", "string")
                                    put("description", "重点关注的屏幕区域或查询目标（可选）")
                                }
                            }
                        }
                    )
                )
            )
        } else emptyList()) + lifeTools.flatMap { it.definitions }
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

        // 整体异常兜底：任何工具实现抛出的异常（如精确闹钟 SecurityException、DAO 故障）
        // 都转为 tool 结果返回给模型，绝不击穿对话主链路；CancellationException 原样上抛保协程取消语义。
        return try {
            // 生活工具优先派发。它们与设备操控无关，且契约保证"不是我的工具
            // 返回 null 而不是抛异常"，因此先问一遍再落到设备工具的 when，
            // 后续扩工具不动这个分支结构。
            for (lifeTool in lifeTools) {
                val lifeResult = lifeTool.execute(call.name, args, call.id)
                if (lifeResult != null) {
                    return lifeResult
                }
            }

            when (call.name) {
            "open_app" -> {
                val appName = args["app_name"]?.jsonPrimitive?.content ?: ""
                val packageName = args["package_name"]?.jsonPrimitive?.content ?: ""
                val resolvedPackage = resolvePackageName(appName, packageName)

                if (resolvedPackage.isBlank()) {
                    ToolExecutionResult(
                        toolCallId = call.id,
                        isSuccess = false,
                        output = "未能解析应用 [$appName] 的包名，请检查应用名称或显式提供 package_name"
                    )
                } else {
                    val action = IntentAction.LaunchApp(packageName = resolvedPackage)
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

            "schedule_wake" -> {
                val delayMinutes = args["delay_minutes"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val botNote = args["bot_note"]?.jsonPrimitive?.content ?: "自定约定"
                val blockDelivery = args["block_delivery"]?.jsonPrimitive?.booleanOrNull ?: false

                val triggerAtMs = timeProvider() + (delayMinutes * 60 * 1000).toLong()
                val entity = scheduleWakeManager?.scheduleWake(triggerAtMs, botNote, blockDelivery)

                ToolExecutionResult(
                    toolCallId = call.id,
                    isSuccess = entity != null,
                    output = if (entity != null) {
                        "已成功设定自定闹钟约定：[$botNote]，将在 $delayMinutes 分钟后触发"
                    } else {
                        "设定闹钟约定：[$botNote]，计划在 $delayMinutes 分钟后触发"
                    }
                )
            }

            "read_screen" -> {
                if (screenCaptureManager == null) {
                    ToolExecutionResult(
                        toolCallId = call.id,
                        isSuccess = false,
                        output = "屏幕读取不可用：未挂载截屏管理器或当前系统无障碍未就绪"
                    )
                } else {
                    val frame = screenCaptureManager.captureScreen()
                    if (frame == null) {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "未能成功捕获屏幕（可能处于金融隐私敏感应用或无障碍截屏未授权）"
                        )
                    } else {
                        val detections = visualDetector?.detectAll(frame.bytes) ?: emptyList()
                        if (detections.isEmpty()) {
                            ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = true,
                                output = "屏幕捕获成功，但未识别到任何可见文字（可能为纯图像或画布空白）"
                            )
                        } else {
                            val formatted = detections.joinToString("\n") { "- 「${it.text}」" }
                            ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = true,
                                output = "屏幕 OCR 识别到 ${detections.size} 项文本：\n$formatted"
                            )
                        }
                    }
                }
            }

            "execute_macro" -> {
                val actionsArray = args["actions"]?.jsonArray
                if (actionsArray.isNullOrEmpty()) {
                    ToolExecutionResult(
                        toolCallId = call.id,
                        isSuccess = false,
                        output = "宏动作执行失败: actions 动作列表为空"
                    )
                } else {
                    val coordinatorActions = mutableListOf<CoordinatorAction>()
                    var parseError: String? = null
                    for ((idx, elem) in actionsArray.withIndex()) {
                        val actObj = elem as? JsonObject
                        if (actObj == null) {
                            parseError = "第 ${idx + 1} 步动作格式错误: 期望为 JSON 对象"
                            break
                        }
                        val type = actObj["type"]?.jsonPrimitive?.content?.lowercase() ?: ""
                        val targetText = actObj["target_text"]?.jsonPrimitive?.content
                        val targetResId = actObj["target_resource_id"]?.jsonPrimitive?.content
                        val query = GroundingQuery(text = targetText, resourceId = targetResId)

                        when (type) {
                            "click" -> {
                                val desc = "点击 ${targetText ?: targetResId ?: "元素"}"
                                coordinatorActions.add(CoordinatorAction.Click(query, desc))
                            }
                            "type" -> {
                                val text = actObj["text"]?.jsonPrimitive?.content ?: ""
                                coordinatorActions.add(CoordinatorAction.Type(query, text, "输入「$text」"))
                            }
                            "press_key" -> {
                                val keyStr = actObj["key"]?.jsonPrimitive?.content ?: "ENTER"
                                val keyCode = KeycodeMapper.resolveKeyCode(keyStr) ?: KeyEvent.KEYCODE_ENTER
                                coordinatorActions.add(CoordinatorAction.PressKey(keyCode, keyStr, "按键 $keyStr"))
                            }
                            "swipe" -> {
                                val dirStr = actObj["direction"]?.jsonPrimitive?.content ?: "up"
                                val dir = when (dirStr.lowercase()) {
                                    "down" -> SwipeDirection.DOWN
                                    "left" -> SwipeDirection.LEFT
                                    "right" -> SwipeDirection.RIGHT
                                    else -> SwipeDirection.UP
                                }
                                coordinatorActions.add(CoordinatorAction.Swipe(dir, humanoid = true, description = "向 $dirStr 滑动"))
                            }
                            "delay" -> {
                                val delayMs = actObj["delay_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L
                                coordinatorActions.add(CoordinatorAction.Delay(delayMs, "等待 ${delayMs}ms"))
                            }
                            else -> {
                                parseError = "第 ${idx + 1} 步动作类型未知: [$type]"
                                break
                            }
                        }
                    }

                    if (parseError != null) {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = parseError
                        )
                    } else {
                        val goal = args["description"]?.jsonPrimitive?.content ?: "连续宏操作 (${coordinatorActions.size} 步)"
                        when (val result = coordinator.executeTask(goal, coordinatorActions)) {
                            is TaskExecutionResult.Success -> {
                                ToolExecutionResult(
                                    toolCallId = call.id,
                                    isSuccess = true,
                                    output = "宏动作分块执行成功 (共 ${result.stepsTaken} 步): ${result.message}"
                                )
                            }
                            is TaskExecutionResult.Failure -> {
                                ToolExecutionResult(
                                    toolCallId = call.id,
                                    isSuccess = false,
                                    output = "宏动作在第 ${result.stepIndex} 步执行失败: ${result.reason}"
                                )
                            }
                            TaskExecutionResult.Cancelled -> {
                                ToolExecutionResult(
                                    toolCallId = call.id,
                                    isSuccess = false,
                                    output = "宏动作执行被取消"
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                ToolExecutionResult(
                    toolCallId = call.id,
                    isSuccess = false,
                    output = "未知的系统工具指令: [${call.name}]"
                )
            }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                toolCallId = call.id,
                isSuccess = false,
                output = "工具执行异常: ${e.message ?: e.javaClass.simpleName}",
                errorMessage = e.message
            )
        }
    }

    fun resolvePackageName(appName: String, explicitPackage: String = ""): String {
        if (explicitPackage.isNotBlank()) return explicitPackage
        val resolved = packageResolver.resolve(appName)
        if (!resolved.isNullOrBlank()) return resolved
        val normalized = appName.trim().lowercase()
        return KNOWN_APP_PACKAGES[normalized]
            ?: KNOWN_APP_PACKAGES[appName.trim()]
            ?: if (appName.contains('.')) appName.trim() else ""
    }

    suspend fun executeToolCalls(calls: List<ToolCall>): List<ToolExecutionResult> {
        return calls.map { executeToolCall(it) }
    }

    companion object {
        val KNOWN_APP_PACKAGES = AppPackageResolver.DEFAULT_KNOWN_APPS

        fun resolvePackageName(appName: String, explicitPackage: String = ""): String {
            if (explicitPackage.isNotBlank()) return explicitPackage
            val normalized = appName.trim().lowercase()
            return KNOWN_APP_PACKAGES[normalized]
                ?: KNOWN_APP_PACKAGES[appName.trim()]
                ?: if (appName.contains('.')) appName.trim() else ""
        }
    }
}
