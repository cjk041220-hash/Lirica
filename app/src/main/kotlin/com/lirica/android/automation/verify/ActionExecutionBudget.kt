package com.lirica.android.automation.verify

/**
 * 任务级硬性预算（主方案5.0 §1.2.3 有界自愈公理 / §5.1 多级观测）。
 *
 * 所有数字都直接来自方案条款，改这里等于改验收标准，必须同步改文档。
 */
data class ActionExecutionBudget(
    /** 单任务原子循环步数上限（§1.2.3） */
    val maxSteps: Int = 12,
    /** 单任务自愈动作累计上限（§1.2.3） */
    val maxSelfHealAttempts: Int = 3,
    /** 单任务云端 VLM 调用硬顶（§5.1 Level 2） */
    val maxVlmCalls: Int = 5,
    /** 单任务总耗时熔断（§1.2.3） */
    val maxWallClockMs: Long = 120_000L,
    /** Level 0 语义裁剪树候选节点上限（§5.1） */
    val maxObservationNodes: Int = 30,
    /** Level 0 Prompt 字符预算（§5.1，8KB） */
    val maxObservationChars: Int = 8_192,
    /** 宏连续回放失败弃用阈值（§5.2） */
    val maxConsecutiveMacroFailures: Int = 2
) {
    init {
        require(maxSteps > 0) { "步数上限必须为正" }
        require(maxSelfHealAttempts >= 0) { "自愈上限不得为负" }
        require(maxVlmCalls >= 0) { "VLM 上限不得为负" }
        require(maxWallClockMs > 0) { "总耗时熔断必须为正" }
    }

    companion object {
        /** 方案 §1.2.3 的默认契约 */
        val DEFAULT = ActionExecutionBudget()
    }
}

/** 预算维度（熔断原因的可枚举表达，便于指标聚合） */
enum class BudgetDimension {
    STEPS,
    SELF_HEAL,
    VLM_CALLS,
    WALL_CLOCK,
    MACRO_FAILURES
}

sealed interface BudgetCheck {
    data object Ok : BudgetCheck
    data class Exhausted(val dimension: BudgetDimension, val reason: String) : BudgetCheck
}

/**
 * 预算账本：纯状态机，无协程、无系统时钟依赖（时钟可注入）。
 *
 * 设计要点：**消耗与检查合一**。分开写就会出现"检查了但忘了扣"的经典漏洞，
 * 而这里的每一项额度都直接对应方案里的一个硬顶数字，漏扣即违约。
 */
class BudgetLedger(
    val budget: ActionExecutionBudget = ActionExecutionBudget.DEFAULT,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private var startedAtMs: Long = 0L
    private var stepsUsed: Int = 0
    private var selfHealUsed: Int = 0
    private var vlmCallsUsed: Int = 0
    private var macroFailures: Int = 0

    val steps: Int get() = stepsUsed
    val selfHealAttempts: Int get() = selfHealUsed
    val vlmCalls: Int get() = vlmCallsUsed
    val macroFailureCount: Int get() = macroFailures

    fun start(now: Long = timeProvider()) {
        startedAtMs = now
        stepsUsed = 0
        selfHealUsed = 0
        vlmCallsUsed = 0
        macroFailures = 0
    }

    fun elapsedMs(now: Long = timeProvider()): Long =
        if (startedAtMs == 0L) 0L else (now - startedAtMs).coerceAtLeast(0L)

    fun remainingMs(now: Long = timeProvider()): Long =
        (budget.maxWallClockMs - elapsedMs(now)).coerceAtLeast(0L)

    /** 申请一步额度：超限返回 [BudgetCheck.Exhausted] 且不递增 */
    fun consumeStep(): BudgetCheck {
        if (stepsUsed >= budget.maxSteps) {
            return BudgetCheck.Exhausted(
                BudgetDimension.STEPS,
                "达到最大执行步数上限 (" + budget.maxSteps + " 步)"
            )
        }
        stepsUsed++
        return BudgetCheck.Ok
    }

    fun consumeSelfHeal(): BudgetCheck {
        if (selfHealUsed >= budget.maxSelfHealAttempts) {
            return BudgetCheck.Exhausted(
                BudgetDimension.SELF_HEAL,
                "自愈动作累计达到上限 (" + budget.maxSelfHealAttempts + " 次)"
            )
        }
        selfHealUsed++
        return BudgetCheck.Ok
    }

    fun consumeVlmCall(): BudgetCheck {
        if (vlmCallsUsed >= budget.maxVlmCalls) {
            return BudgetCheck.Exhausted(
                BudgetDimension.VLM_CALLS,
                "云端 VLM 调用达到硬顶 (" + budget.maxVlmCalls + " 次)"
            )
        }
        vlmCallsUsed++
        return BudgetCheck.Ok
    }

    fun recordMacroFailure(): BudgetCheck {
        macroFailures++
        if (macroFailures >= budget.maxConsecutiveMacroFailures) {
            return BudgetCheck.Exhausted(
                BudgetDimension.MACRO_FAILURES,
                "宏连续回放失败达到弃用阈值 (" + budget.maxConsecutiveMacroFailures + " 次)"
            )
        }
        return BudgetCheck.Ok
    }

    fun resetMacroFailures() {
        macroFailures = 0
    }

    fun checkDeadline(now: Long = timeProvider()): BudgetCheck {
        val elapsed = elapsedMs(now)
        return if (elapsed >= budget.maxWallClockMs) {
            BudgetCheck.Exhausted(
                BudgetDimension.WALL_CLOCK,
                "任务总耗时熔断 (" + elapsed + "ms >= " + budget.maxWallClockMs + "ms)"
            )
        } else {
            BudgetCheck.Ok
        }
    }
}
