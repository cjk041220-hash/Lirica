package com.xiaoxi.android.proactivity

/**
 * 任务断点类型枚举
 */
enum class BreakpointType {
    NONE,
    SCREEN_UNLOCK,
    APP_SWITCH,
    RETURN_TO_LAUNCHER,
    LONG_IDLE
}

/**
 * 任务断点检测结果
 * @param isBreakpoint 是否命中天然任务断点
 * @param type 断点类型
 * @param bonusFactor 破冰动机增益倍率 (例如 1.2 ~ 1.4)
 */
data class BreakpointResult(
    val isBreakpoint: Boolean,
    val type: BreakpointType,
    val bonusFactor: Double = 1.0
)

/**
 * 主动动作裁决类别
 */
enum class ProactiveAction {
    /** 保持绝对静默，不进行任何打扰或显式反应 */
    SILENT,
    /** 内隐动机已触发，但打扰代价过高或未及强断点，仅存入未完成念头池并触发伴生体微表情 */
    INNER_THOUGHT_ONLY,
    /** 综合有效动机超越打断阈值且打扰代价适宜，允许主动弹出破冰气泡 */
    PROACTIVE_INTERRUPT
}

/**
 * 最终沉默裁决与主动性门控输出结果
 *
 * @param action 裁决决定的主动性动作
 * @param innerMotivation 原始内隐动机分数
 * @param interruptCost 当前情境打扰代价 Cost_int(t)
 * @param effectiveMotivation 结合断点加成与代价折减后的有效动机
 * @param breakpoint 伴随的断点检测结果
 * @param reason 决策判定原因
 */
data class DecisionResult(
    val action: ProactiveAction,
    val innerMotivation: Double,
    val interruptCost: Double,
    val effectiveMotivation: Double,
    val breakpoint: BreakpointResult,
    val reason: String
)
