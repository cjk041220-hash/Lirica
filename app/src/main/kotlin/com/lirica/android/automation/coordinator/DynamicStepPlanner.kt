package com.lirica.android.automation.coordinator

import android.view.KeyEvent
import com.lirica.android.automation.backend.KeycodeMapper
import com.lirica.android.automation.grounding.GroundingQuery
import com.lirica.android.automation.observe.ObservationSnapshot
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.trace.TokenUsage
import com.lirica.android.common.logging.LiricaLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 单步决策器契约（M5.2 动态 ReAct 闭环的"Decide"环节）。
 *
 * 把它抽成接口而不是直接耦合 LLM，是为了让闭环的**机制**（预算熔断、自愈、
 * 核销、抢占）能在纯 JVM 单测里被穷举——LLM 只是其中一种决策实现。
 * 这符合方案"保持聊天层工具面稳定、只升级执行模式"的裁决：换决策源
 * 不该动执行引擎一行代码。
 */
fun interface DynamicStepPlanner {
    /**
     * 依据当前观测决定下一步。
     * @return 计划；返回 null 表示决策失败（由闭环按自愈预算处理）
     */
    suspend fun plan(context: PlanningContext): PlannedStep?
}

/**
 * LLM 决策器：把观测快照渲染成紧凑 Prompt，解析单步 JSON 指令。
 *
 * 三个刻意的工程约束：
 * 1. **单步单决策**：每次只允许返回一个动作，杜绝"一次吐五步"的批量幻觉；
 * 2. **引用驱动**：动作只能通过 refId 指代目标，不能自造坐标——坐标自造会让
 *    接地层的安全闸门失去审查对象（拿不到目标文本就无法判定是否敏感控件）；
 * 3. **解析失败即 null**：不猜、不降级成"随便点一下"。宁可失败并触发
 *    有界自愈，也不能把未解析的意图打到屏幕上。
 */
class LlmDynamicStepPlanner(
    private val chatInvoker: suspend (prompt: String) -> String,
    private val maxParseAttempts: Int = DEFAULT_PARSE_ATTEMPTS,
    private val usageSink: (TokenUsage) -> Unit = {}
) : DynamicStepPlanner {

    override suspend fun plan(context: PlanningContext): PlannedStep? {
        val prompt = buildPrompt(context)
        repeat(maxParseAttempts) { attempt ->
            val raw = runCatching { chatInvoker(prompt) }.getOrElse { throwable ->
                LiricaLog.w("LlmDynamicStepPlanner", "决策调用失败（第 " + (attempt + 1) + " 次）", throwable)
                return null
            }
            usageSink(
                TokenUsage(
                    promptTokens = estimateTokens(prompt),
                    completionTokens = estimateTokens(raw)
                )
            )
            val parsed = parse(raw, context.observation)
            if (parsed != null) return parsed
            LiricaLog.w("LlmDynamicStepPlanner", "决策输出无法解析为单步指令，重试中")
        }
        return null
    }

    /** Prompt 渲染：观测层已经压到 <=8KB，这里只做模板拼接 */
    internal fun buildPrompt(context: PlanningContext): String = buildString {
        append("你是行动决策器。只输出一个 JSON 对象，不要任何解释或 Markdown 代码块。\n")
        append("目标：").append(context.goal).append("\n")
        append("模式：").append(
            if (context.mode == ActionExecutionMode.ROAMING) {
                "漫游只读（禁止关注/点赞/投币/评论/播放/购买等会留下痕迹的互动）"
            } else {
                "共享（可完整操控）"
            }
        ).append("\n")
        append("进度：第 ").append(context.stepIndex).append("/").append(context.maxSteps).append(" 步\n")

        if (context.history.isNotEmpty()) {
            append("已完成：\n")
            context.history.takeLast(MAX_HISTORY_LINES).forEach { record ->
                append(record.index).append(". ").append(record.description)
                record.verification?.let { verification ->
                    append(" -> ").append(verificationLabel(verification))
                }
                append("\n")
            }
        }

        append("当前界面：\n").append(context.observation.promptText).append("\n")

        append("输出格式（严格）：\n")
        append("{\"think\":\"一句话判断\",\"action\":{\"type\":\"click\",\"ref\":\"n0\"},\"expect\":\"点击后应出现什么\"}\n")
        append("type 取值与必填字段：\n")
        append("- click: ref\n")
        append("- type: ref + text\n")
        append("- swipe: direction(up/down/left/right)\n")
        append("- back: 无\n")
        append("- wait: delay_ms\n")
        append("- finish: summary（目标已达成或确认无法达成时）\n")
    }

    private fun verificationLabel(verification: com.lirica.android.automation.verify.ActionVerification): String =
        when (verification) {
            is com.lirica.android.automation.verify.ActionVerification.EffectObserved -> "已生效"
            is com.lirica.android.automation.verify.ActionVerification.NoVisibleEffect -> "未见变化"
            is com.lirica.android.automation.verify.ActionVerification.Unstable -> "页面仍在变化"
            is com.lirica.android.automation.verify.ActionVerification.Unavailable -> "页面不可读"
        }

    /**
     * 解析单步指令。
     *
     * 容错策略：容忍 Markdown 包裹、前后废话、字段别名（ref/ref_id/refId），
     * 但不容忍"动作类型缺失"或"缺少必填字段"——那两种情况返回 null。
     */
    internal fun parse(raw: String, observation: ObservationSnapshot? = null): PlannedStep? {
        val jsonText = extractJsonObject(raw) ?: return null
        val root = runCatching { LENIENT_JSON.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
        val action = (root["action"] as? JsonObject) ?: return null
        val expect = root["expect"]?.jsonPrimitive?.contentOrNullSafe()
        val think = root["think"]?.jsonPrimitive?.contentOrNullSafe()

        val type = action["type"]?.jsonPrimitive?.contentOrNullSafe()?.lowercase() ?: return null
        val ref = action.firstString("ref", "ref_id", "refId", "target")

        return when (type) {
            "click" -> {
                val query = refQuery(ref, observation) ?: return null
                PlannedStep.act(
                    action = CoordinatorAction.Click(query = query, description = "点击 " + (ref ?: "")),
                    refId = ref,
                    expectation = expect,
                    narration = think
                )
            }
            "type", "input" -> {
                val query = refQuery(ref, observation) ?: return null
                val text = action.firstString("text", "content", "value") ?: return null
                PlannedStep.act(
                    action = CoordinatorAction.Type(query = query, text = text, description = "输入文本"),
                    refId = ref,
                    expectation = expect,
                    narration = think
                )
            }
            "swipe", "scroll" -> {
                val direction = action.firstString("direction", "dir")?.lowercase() ?: "up"
                val swipeDirection = when (direction) {
                    "up" -> SwipeDirection.UP
                    "down" -> SwipeDirection.DOWN
                    "left" -> SwipeDirection.LEFT
                    "right" -> SwipeDirection.RIGHT
                    else -> return null
                }
                PlannedStep.act(
                    action = CoordinatorAction.Swipe(direction = swipeDirection),
                    expectation = expect,
                    narration = think
                )
            }
            "press_key", "key" -> {
                val keyStr = action.firstString("key", "name") ?: "ENTER"
                val keyCode = KeycodeMapper.resolveKeyCode(keyStr) ?: KeyEvent.KEYCODE_ENTER
                PlannedStep.act(
                    action = CoordinatorAction.PressKey(keyCode = keyCode, keyName = keyStr, description = "按键 " + keyStr),
                    expectation = expect,
                    narration = think
                )
            }
            "enter" -> PlannedStep.act(
                action = CoordinatorAction.PressKey(keyCode = KeyEvent.KEYCODE_ENTER, keyName = "ENTER", description = "按回车键"),
                expectation = expect,
                narration = think
            )
            "search" -> PlannedStep.act(
                action = CoordinatorAction.PressKey(keyCode = KeyEvent.KEYCODE_SEARCH, keyName = "SEARCH", description = "按搜索键"),
                expectation = expect,
                narration = think
            )
            "back" -> PlannedStep.act(action = CoordinatorAction.Backtrack(), expectation = expect, narration = think)
            "wait" -> {
                val delayMs = action.firstString("delay_ms", "delayMs", "ms")?.toLongOrNull() ?: DEFAULT_WAIT_MS
                PlannedStep.act(action = CoordinatorAction.Delay(delayMs.coerceIn(100L, MAX_WAIT_MS)), expectation = expect, narration = think)
            }
            "finish" -> {
                val summary = action.firstString("summary", "message") ?: "任务结束"
                PlannedStep.finish(summary)
            }
            else -> null
        }
    }

    /**
     * refId → 接地查询。
     *
     * 决策层只给引用，文本/资源 ID 由**本层从观测快照回填**：这样既保证
     * 接地层能拿到目标语义（安全闸门才有审查对象），又不给模型自造坐标的机会。
     */
    private fun refQuery(ref: String?, snapshot: ObservationSnapshot?): GroundingQuery? {
        if (ref.isNullOrBlank()) {
            // 无引用时退化为"整屏语义描述"，仅当观测层确实没有节点时才会走到
            return snapshot?.let { GroundingQuery(semanticDescription = it.activityName) }
        }
        // 引用必须在**本轮观测**里真实存在：找不到就明确失败，交由闭环刷新观测后重试。
        // 伪造一个 semanticDescription="n9" 的查询只会让接地层白跑一趟并产出误导性日志。
        val node = snapshot?.nodeByRef(ref) ?: return null
        return GroundingQuery(
            text = node.text,
            resourceId = node.resourceId,
            semanticDescription = node.contentDescription ?: node.text,
            normalizedAnchorX = node.normalizedX,
            normalizedAnchorY = node.normalizedY
        )
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    private fun JsonObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key]?.jsonPrimitive?.contentOrNullSafe()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN).coerceAtLeast(0)

    companion object {
        const val DEFAULT_PARSE_ATTEMPTS = 2
        const val DEFAULT_WAIT_MS = 800L
        const val MAX_WAIT_MS = 5_000L
        const val MAX_HISTORY_LINES = 8

        /**
         * Token 估算字符比。
         *
         * 诚实披露：这是**估算**而非网关回传的真实用量。中英混排下 4 字符/token
         * 是保守下界（中文约 1.5 字符/token，故估算偏高，用于预算告警是安全的）。
         * 真实用量需网关在流式响应里回传 usage 字段，属后续迭代。
         */
        const val CHARS_PER_TOKEN = 4

        private val LENIENT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
