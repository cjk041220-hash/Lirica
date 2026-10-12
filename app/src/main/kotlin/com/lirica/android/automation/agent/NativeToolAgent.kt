package com.lirica.android.automation.agent

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.automation.backend.KeycodeMapper
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.perception.AccessibilityServiceHolder
import kotlinx.coroutines.delay
import com.lirica.android.automation.capture.ScreenCaptureManager
import com.lirica.android.automation.coordinator.CoordinatorAction
import com.lirica.android.automation.coordinator.HelixGuiCoordinator
import com.lirica.android.automation.coordinator.SwipeDirection
import com.lirica.android.automation.coordinator.TaskExecutionResult
import com.lirica.android.automation.grounding.GroundingQuery
import com.lirica.android.automation.grounding.VisualElementDetector
import com.lirica.android.automation.intent.DispatchResult
import com.lirica.android.automation.intent.IntentAction
import com.lirica.android.automation.intent.IntentActionDispatcher
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.ToolCall
import com.lirica.android.network.model.ToolDefinition
import com.lirica.android.network.model.ToolFunction
import com.lirica.android.proactivity.ScheduleWakeManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
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
    private val screenWidthProvider: (() -> Int)? = null,
    private val screenHeightProvider: (() -> Int)? = null,
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
                            putJsonObject("index") {
                                put("type", "integer")
                                put("description", "目标元素序号（可选，前序屏幕观察返回的 [1]、[2] 编号，优选使用以保证 100% 精准点击）")
                            }
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
                            putJsonObject("index") {
                                put("type", "integer")
                                put("description", "目标输入框序号（可选，对应前序屏幕观察返回的编号）")
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
                                put("description", "Lirica自己用第一人称记录的定闹钟原因（例如：'半小时后提醒喝水'）")
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
                    description = "执行多步自动化操作或启动自主动态闭环。若只需达成高阶目标（如'在应用内搜索xxx'），可直接在 description 中给出目标并不传 actions，系统将自动进入具备观察、决策、核销与自愈能力的单步动态闭环；若已知具体原子动作序列，亦可传入 actions 列表",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("description") {
                                put("type", "string")
                                put("description", "本次代行操作的高阶目标或总结（不传 actions 时作为动态闭环的核心目标）")
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
                            add(JsonPrimitive("description"))
                        }
                    }
                )
            ),
        ) + (if (screenCaptureManager != null) {
            listOf(
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "read_screen",
                        description = "捕获并读取当前手机屏幕上所有可见元素、文本与输入框（建议在打开应用后、跳转页面后或需要核对界面状态时调用）",
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
        LiricaLog.i("NativeToolAgent", "开始执行工具: ${call.name}, 参数: ${call.arguments}")
        val args = runCatching {
            json.parseToJsonElement(call.arguments) as? JsonObject
        }.getOrNull()

        if (args == null) {
            LiricaLog.w("NativeToolAgent", "工具参数解析失败: ${call.name}, raw=${call.arguments}")
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
                            val screenInfo = awaitPackageSettling(resolvedPackage)
                            ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = true,
                                output = "应用 [$appName] 已成功打开并置于前台。$screenInfo"
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
                val index = args["index"]?.jsonPrimitive?.intOrNull
                    ?: args["index"]?.jsonPrimitive?.content?.toIntOrNull()
                val rawText = args["text"]?.jsonPrimitive?.content
                    ?: args["target_text"]?.jsonPrimitive?.content
                    ?: args["target"]?.jsonPrimitive?.content
                    ?: args["name"]?.jsonPrimitive?.content
                    ?: args["query"]?.jsonPrimitive?.content
                    ?: args["element"]?.jsonPrimitive?.content
                    ?: args["label"]?.jsonPrimitive?.content
                    ?: args["content"]?.jsonPrimitive?.content
                val resourceId = args["resource_id"]?.jsonPrimitive?.content
                    ?: args["target_resource_id"]?.jsonPrimitive?.content
                    ?: args["id"]?.jsonPrimitive?.content
                    ?: args["view_id"]?.jsonPrimitive?.content

                // 若指定了 index 但缓存尚未建立，立即现场捕获当前屏幕元素
                if (index != null && lastIndexedElements.isEmpty()) {
                    captureScreenSummary()
                }

                val indexed = if (index != null && index in 1..lastIndexedElements.size) {
                    lastIndexedElements[index - 1]
                } else null

                val targetText = indexed?.label ?: rawText
                val targetRes = indexed?.resourceId ?: resourceId
                val targetAnchorX = indexed?.normalizedCenterX
                val targetAnchorY = indexed?.normalizedCenterY

                if (targetText == null && targetRes == null && targetAnchorX == null) {
                    val screenInfo = captureScreenSummary()
                    return ToolExecutionResult(
                        toolCallId = call.id,
                        isSuccess = false,
                        output = "点击操作失败: 未能解析目标元素（请提供有效的 index 编号或 text 文本）。$screenInfo"
                    )
                }

                val desc = args["description"]?.jsonPrimitive?.content
                    ?: "点击元素 ${targetText ?: targetRes ?: "index $index"}"

                val action = CoordinatorAction.Click(
                    query = GroundingQuery(
                        text = targetText,
                        resourceId = targetRes,
                        normalizedAnchorX = targetAnchorX,
                        normalizedAnchorY = targetAnchorY
                    ),
                    description = desc
                )

                when (val result = coordinator.executeTask("点击元素", listOf(action))) {
                    is TaskExecutionResult.Success -> {
                        delay(400L)
                        val screenInfo = captureScreenSummary()
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = "点击操作完成: ${result.message}。$screenInfo"
                        )
                    }
                    is TaskExecutionResult.Failure -> {
                        val failMsg = result.honestNarrative?.takeIf { it.isNotBlank() }
                            ?: "点击操作失败: ${result.reason}"
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = failMsg
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
                val text = args["text"]?.jsonPrimitive?.content
                    ?: args["content"]?.jsonPrimitive?.content
                    ?: args["input"]?.jsonPrimitive?.content
                    ?: args["value"]?.jsonPrimitive?.content
                    ?: ""
                val index = args["index"]?.jsonPrimitive?.intOrNull
                    ?: args["index"]?.jsonPrimitive?.content?.toIntOrNull()

                if (index != null && lastIndexedElements.isEmpty()) {
                    captureScreenSummary()
                }

                val indexed = if (index != null && index in 1..lastIndexedElements.size) {
                    lastIndexedElements[index - 1]
                } else null

                val targetResourceId = indexed?.resourceId
                    ?: args["target_resource_id"]?.jsonPrimitive?.content
                    ?: args["resource_id"]?.jsonPrimitive?.content
                    ?: args["id"]?.jsonPrimitive?.content

                // 当模型只传了待输入文本 text 而未指定 target_text / index 时，
                // 默认指向"输入框"，由 HybridGroundingEngine 自动匹配当前活动或可见 EditText，
                // 彻底消除 GroundingQuery(text=null, ...) 报错！
                val targetText = indexed?.label
                    ?: args["target_text"]?.jsonPrimitive?.content
                    ?: args["target"]?.jsonPrimitive?.content
                    ?: args["query"]?.jsonPrimitive?.content
                    ?: args["element"]?.jsonPrimitive?.content
                    ?: if (indexed == null && targetResourceId == null) "输入框" else null

                val targetAnchorX = indexed?.normalizedCenterX
                val targetAnchorY = indexed?.normalizedCenterY

                val action = CoordinatorAction.Type(
                    query = GroundingQuery(
                        text = targetText,
                        resourceId = targetResourceId,
                        normalizedAnchorX = targetAnchorX,
                        normalizedAnchorY = targetAnchorY
                    ),
                    text = text,
                    description = "键入文本 $text"
                )

                when (val result = coordinator.executeTask("输入文本", listOf(action))) {
                    is TaskExecutionResult.Success -> {
                        delay(300L)
                        val screenInfo = captureScreenSummary()
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = "输入操作完成: ${result.message}。$screenInfo"
                        )
                    }
                    is TaskExecutionResult.Failure -> {
                        val failMsg = result.honestNarrative?.takeIf { it.isNotBlank() }
                            ?: "输入操作失败: ${result.reason}"
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = failMsg
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
                        delay(400L)
                        val screenInfo = captureScreenSummary()
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = "滑动操作完成。$screenInfo"
                        )
                    }
                    is TaskExecutionResult.Failure -> {
                        val failMsg = result.honestNarrative?.takeIf { it.isNotBlank() }
                            ?: "滑动操作失败: ${result.reason}"
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = failMsg
                        )
                    }
                    TaskExecutionResult.Cancelled -> {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "滑动操作被取消"
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
                    val accessibilitySummary = captureScreenSummary()
                    ToolExecutionResult(
                        toolCallId = call.id,
                        isSuccess = accessibilitySummary.isNotBlank(),
                        output = if (accessibilitySummary.isNotBlank()) "屏幕读取完成。$accessibilitySummary" else "屏幕读取不可用：未挂载截屏管理器或当前系统无障碍未就绪"
                    )
                } else {
                    val frame = screenCaptureManager.captureScreen()
                    if (frame == null) {
                        val accessibilitySummary = captureScreenSummary()
                        if (accessibilitySummary.isNotBlank()) {
                            ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = true,
                                output = "屏幕读取完成（无障碍节点树扫描）：$accessibilitySummary"
                            )
                        } else {
                            ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = false,
                                output = "未能成功捕获屏幕（可能处于金融隐私敏感应用或无障碍截屏未授权）"
                            )
                        }
                    } else {
                        val detections = visualDetector?.detectAll(frame.bytes) ?: emptyList()
                        val accessibilitySummary = captureScreenSummary()
                        val ocrText = if (detections.isEmpty()) {
                            "屏幕捕获成功，但未识别到任何可见文字（可能为纯图像或画布空白）"
                        } else {
                            val formatted = detections.joinToString("\n") { "- 「${it.text}」" }
                            "屏幕 OCR 识别到 ${detections.size} 项文本：\n$formatted"
                        }
                        val combined = if (accessibilitySummary.isNotBlank()) "$ocrText\n$accessibilitySummary" else ocrText
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = true,
                            output = combined
                        )
                    }
                }
            }

            "execute_macro" -> {
                val actionsArray = args["actions"]?.jsonArray
                if (actionsArray.isNullOrEmpty()) {
                    // M5.2（P1-1 落地）：未给出显式动作序列时，本工具自动升级为
                    // 单步动态 ReAct 闭环——只给目标，由她逐步观测、决策、核销、自愈。
                    // 关键：**工具数量与名称保持不变**（仍是 8 个），聊天层契约零改动，
                    // 模型不需要知道执行模式换了，只是"她变聪明了"。
                    val dynamicGoal = args["description"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (dynamicGoal.isEmpty()) {
                        ToolExecutionResult(
                            toolCallId = call.id,
                            isSuccess = false,
                            output = "宏动作执行失败: actions 动作列表为空，且未提供 description 目标描述"
                        )
                    } else {
                        when (val result = coordinator.executeAutonomousTask(
                            com.lirica.android.automation.coordinator.AutonomousTaskRequest(goal = dynamicGoal)
                        )) {
                            is TaskExecutionResult.Success -> ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = true,
                                output = "动态闭环执行成功 (共 " + result.stepsTaken + " 步): " + result.message
                            )
                            is TaskExecutionResult.Failure -> ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = false,
                                output = result.honestNarrative?.takeIf { it.isNotBlank() }
                                    ?: "动态闭环在第 " + result.stepIndex + " 步失败: " + result.reason
                            )
                            TaskExecutionResult.Cancelled -> ToolExecutionResult(
                                toolCallId = call.id,
                                isSuccess = false,
                                output = "动态闭环执行被取消"
                            )
                        }
                    }
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
                            ?: actObj["target"]?.jsonPrimitive?.content
                            ?: actObj["query"]?.jsonPrimitive?.content
                        val targetResId = actObj["target_resource_id"]?.jsonPrimitive?.content
                            ?: actObj["resource_id"]?.jsonPrimitive?.content
                            ?: actObj["id"]?.jsonPrimitive?.content

                        when (type) {
                            "click" -> {
                                val resolvedClickText = targetText ?: actObj["text"]?.jsonPrimitive?.content
                                val query = GroundingQuery(text = resolvedClickText, resourceId = targetResId)
                                val desc = "点击 ${resolvedClickText ?: targetResId ?: "元素"}"
                                coordinatorActions.add(CoordinatorAction.Click(query, desc))
                            }
                            "type" -> {
                                val text = actObj["text"]?.jsonPrimitive?.content
                                    ?: actObj["content"]?.jsonPrimitive?.content
                                    ?: ""
                                val resolvedTypeTarget = targetText ?: if (targetResId == null) "输入框" else null
                                val query = GroundingQuery(text = resolvedTypeTarget, resourceId = targetResId)
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
                                val failMsg = result.honestNarrative?.takeIf { it.isNotBlank() }
                                    ?: "宏动作在第 ${result.stepIndex} 步执行失败: ${result.reason}"
                                ToolExecutionResult(
                                    toolCallId = call.id,
                                    isSuccess = false,
                                    output = failMsg
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

    private data class IndexedScreenElement(
        val index: Int,
        val label: String,
        val tag: String,
        val resourceId: String?,
        val normalizedCenterX: Int?,
        val normalizedCenterY: Int?
    )

    private var lastIndexedElements = listOf<IndexedScreenElement>()

    /**
     * 智能页面就绪等待：解决切屏后 500ms 盲拍白屏/桌面残留问题，
     * 轮询等待目标包名进入前台且根节点完成初步首屏布局再捕获屏幕摘要。
     */
    private suspend fun awaitPackageSettling(targetPackage: String, maxWaitMs: Long = 2500L): String {
        val start = timeProvider()
        delay(300L)
        while (timeProvider() - start < maxWaitMs) {
            val service = AccessibilityServiceHolder.get() ?: break
            val root = service.rootInActiveWindow
            val currentPkg = root?.packageName?.toString().orEmpty()
            if (currentPkg.isNotBlank() && (targetPackage.isBlank() || currentPkg == targetPackage)) {
                if (root.childCount > 0) {
                    delay(200L)
                    break
                }
            }
            delay(150L)
        }
        return captureScreenSummary()
    }

    private fun captureScreenSummary(): String {
        val service = AccessibilityServiceHolder.get() ?: return ""
        val root = service.rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString().orEmpty()
        val rawElements = mutableListOf<IndexedScreenElement>()
        val tempRect = android.graphics.Rect()
        val dm = service.resources.displayMetrics
        val sw = kotlin.math.max(1, screenWidthProvider?.invoke()?.takeIf { it > 0 } ?: dm.widthPixels)
        val sh = kotlin.math.max(1, screenHeightProvider?.invoke()?.takeIf { it > 0 } ?: dm.heightPixels)

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 12 || rawElements.size >= 60) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val idName = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

            val label = when {
                !text.isNullOrBlank() -> text
                !desc.isNullOrBlank() -> desc
                node.isEditable -> "输入框"
                node.isClickable && idName != null -> "图标($idName)"
                else -> null
            }

            if (!label.isNullOrBlank()) {
                node.getBoundsInScreen(tempRect)
                val cx = if (tempRect.width() > 0) ((tempRect.centerX().toFloat() / sw) * 1000).toInt().coerceIn(0, 1000) else null
                val cy = if (tempRect.height() > 0) ((tempRect.centerY().toFloat() / sh) * 1000).toInt().coerceIn(0, 1000) else null
                val tag = when {
                    node.isEditable -> "输入框"
                    node.isClickable -> "按钮"
                    else -> "文字"
                }
                rawElements.add(
                    IndexedScreenElement(
                        index = rawElements.size + 1,
                        label = label,
                        tag = tag,
                        resourceId = node.viewIdResourceName,
                        normalizedCenterX = cx,
                        normalizedCenterY = cy
                    )
                )
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }
        walk(root, 0)

        // 空间去重：若两项标签相同且中心坐标相近，仅保留一项；若标签相同但在不同空间位置，保留各自编号
        val uniqueElements = mutableListOf<IndexedScreenElement>()
        for (elem in rawElements) {
            val isDuplicate = uniqueElements.any { u ->
                u.label == elem.label && (
                    elem.normalizedCenterX == null || u.normalizedCenterX == null ||
                        elem.normalizedCenterY == null || u.normalizedCenterY == null ||
                        (kotlin.math.abs(u.normalizedCenterX - elem.normalizedCenterX) < 60 &&
                            kotlin.math.abs(u.normalizedCenterY - elem.normalizedCenterY) < 60)
                    )
            }
            if (!isDuplicate) {
                uniqueElements.add(elem)
            }
        }

        val finalElements = uniqueElements
            .take(25)
            .mapIndexed { idx, elem -> elem.copy(index = idx + 1) }

        lastIndexedElements = finalElements
        if (finalElements.isEmpty()) return if (pkg.isNotBlank()) "\n【当前前台: $pkg】" else ""
        return "\n【当前前台: $pkg，屏幕可见元素】\n" + finalElements.joinToString("\n") { "• [${it.index}] [${it.tag}] \"${it.label}\"" }
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
