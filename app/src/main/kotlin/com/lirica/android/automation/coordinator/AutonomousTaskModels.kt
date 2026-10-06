package com.lirica.android.automation.coordinator

import com.lirica.android.automation.observe.ObservationSnapshot
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.trace.TraceFinalState
import com.lirica.android.automation.verify.ActionVerification
import com.lirica.android.automation.grounding.ElementBounds

/**
 * 原子动作落地结果（M5.2 单步闭环的执行侧契约）。
 *
 * 为什么从 Boolean 升级成密封类型：Boolean 无法区分"被安全闸门拦下"
 * 与"技术性失败"，而这两种结果对用户的话术、对自愈策略、对指标聚合
 * 都是完全不同的东西。历史实现里两者都走 Failed 分支，导致
 * "她告诉我点不动，其实是被自己的安全策略拦住了"这类自相矛盾的汇报。
 */
sealed interface AtomicExecutionOutcome {
    /** 动作已派发成功（不代表页面已生效，生效由核销层判定） */
    data class Succeeded(
        val description: String,
        val targetBounds: ElementBounds? = null
    ) : AtomicExecutionOutcome

    /** Finish 动作：任务在语义上完成 */
    data class Completed(val summary: String) : AtomicExecutionOutcome

    /** 安全闸门物理熔断 */
    data class Blocked(
        val rule: String,
        val reason: String,
        val honestNarrative: String? = null
    ) : AtomicExecutionOutcome

    /** 技术性失败（找不到元素、手势失败、文本注入失败…） */
    data class Failed(
        val reason: String,
        val honestNarrative: String? = null
    ) : AtomicExecutionOutcome

    /** 二次确认未通过或超时 */
    data class ConfirmationDeclined(val reason: String) : AtomicExecutionOutcome

    data object Cancelled : AtomicExecutionOutcome
}

/**
 * 原子动作执行器：静态列表执行与动态 ReAct 闭环**共用同一个**落地通道。
 *
 * 这是方案 §二「AutonomousActionLoop = Coordinator 内部升级」的落地方式：
 * 不新建平行执行引擎，只把 Coordinator 已有的单步分派能力暴露成可复用契约，
 * 从结构上封死"双决策引擎"风险（P1-1）。
 */
interface AtomicActionExecutor {
    suspend fun executeAtomic(
        action: CoordinatorAction,
        stepIndex: Int,
        totalSteps: Int
    ): AtomicExecutionOutcome
}

/**
 * 动态闭环任务请求。
 */
data class AutonomousTaskRequest(
    val goal: String,
    /** 非空时启用经验轨迹录制与回放（需显式声明的稳定意图签名） */
    val intentSignature: String? = null,
    /** 显式槽位注入值（一期不做模糊参数推断） */
    val slots: Map<String, String> = emptyMap(),
    /** 覆盖预算步数（默认取 ActionExecutionBudget.maxSteps） */
    val maxSteps: Int? = null
)

/**
 * 动态闭环任务结果。
 */
sealed interface AutonomousTaskResult {
    data class Success(
        val message: String,
        val steps: Int,
        val macroId: String? = null,
        val replayedFromMacro: Boolean = false
    ) : AutonomousTaskResult

    data class Failure(
        val reason: String,
        val stepIndex: Int,
        val honestNarrative: String? = null,
        val finalState: TraceFinalState = TraceFinalState.FAILED
    ) : AutonomousTaskResult

    data object Cancelled : AutonomousTaskResult

    /**
     * 安全闸门拦截。
     *
     * [negotiationHint] 非空表示这不是"死路"，而是漫游模式下需要用户协商的部分
     * （§八 自主风险交互）：心智层应把它渲染成一句年上口吻的征询，而不是失败播报。
     */
    data class PolicyBlocked(
        val rule: String,
        val reason: String,
        val honestReason: String,
        val negotiationHint: String? = null
    ) : AutonomousTaskResult

    /** 用户抢占导致任务暂停且未在窗口内获得"继续" */
    data class UserInterrupted(val reason: String, val stepIndex: Int) : AutonomousTaskResult
}

/**
 * 决策层输出：单步计划。
 */
data class PlannedStep(
    /** 要执行的动作；[finish] 为 true 时可为 null */
    val action: CoordinatorAction?,
    /** 目标节点引用（观测层分配的 refId），用于核销时回探控件状态 */
    val refId: String? = null,
    /** 决策层对"这一步之后应该看到什么"的自我预期，写入轨迹供复盘 */
    val expectation: String? = null,
    val finish: Boolean = false,
    val summary: String = "",
    /** 决策层给出的简短自述（可选，供具身气泡展示） */
    val narration: String? = null
) {
    companion object {
        fun act(
            action: CoordinatorAction,
            refId: String? = null,
            expectation: String? = null,
            narration: String? = null
        ): PlannedStep = PlannedStep(
            action = action,
            refId = refId,
            expectation = expectation,
            narration = narration
        )

        fun finish(summary: String): PlannedStep =
            PlannedStep(action = null, finish = true, summary = summary)
    }
}

/** 已完成步的历史记录（喂回决策层的短期记忆） */
data class StepRecord(
    val index: Int,
    val description: String,
    val verification: ActionVerification?,
    val expectation: String? = null
)

/** 决策上下文：决策层看到的全部信息 */
data class PlanningContext(
    val goal: String,
    val stepIndex: Int,
    val maxSteps: Int,
    val observation: ObservationSnapshot,
    val history: List<StepRecord>,
    val mode: ActionExecutionMode
)
