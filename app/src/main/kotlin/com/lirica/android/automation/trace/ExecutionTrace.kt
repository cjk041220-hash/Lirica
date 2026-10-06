package com.lirica.android.automation.trace

import com.lirica.android.automation.observe.ObservationLevel
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.verify.ActionVerification
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 单步执行轨迹（主方案5.0 §九 可观测性 · P2-4 落地）。
 */
data class TraceStep(
    val index: Int,
    val description: String,
    val observationLevel: ObservationLevel?,
    val actionKind: String,
    val startedAtMs: Long,
    val durationMs: Long,
    /** 核销结论的可枚举表述（与 [ActionVerification] 同构） */
    val verification: VerificationOutcome,
    val isSelfHealing: Boolean = false,
    val isMacroReplay: Boolean = false,
    /** 该步是否命中了漫游只读拦截 */
    val blockedByPolicy: Boolean = false
) {
    enum class VerificationOutcome {
        EFFECT_OBSERVED,
        NO_VISIBLE_EFFECT,
        UNSTABLE,
        UNAVAILABLE,
        NOT_APPLICABLE
    }
}

/** 宏命中记录（M5.3 命中率指标的数据源） */
data class MacroHitRecord(
    val macroId: String,
    val intentSignature: String,
    val startPageVerified: Boolean,
    val replaySteps: Int,
    val downgradedToReAct: Boolean,
    val downgradeReason: String? = null
)

/**
 * Token 消耗埋点（复审 R8 建议：没有 token 埋点，"后续按实际消耗做动态预算"
 * 的承诺无法兑现）。
 */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens
    )
}

enum class TraceFinalState {
    SUCCESS,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,
    POLICY_BLOCKED,
    USER_INTERRUPTED
}

/**
 * 执行轨迹实体契约（方案 §九 第 1 条逐字段对应）。
 */
data class ExecutionTrace(
    val taskId: String,
    val goal: String,
    val mode: ActionExecutionMode,
    val startTimeMs: Long,
    val durationMs: Long,
    val finalState: TraceFinalState,
    val steps: List<TraceStep>,
    val vlmCallCount: Int,
    val selfHealAttempts: Int,
    val interruptionCount: Int,
    val macroHit: MacroHitRecord?,
    val tokenUsage: TokenUsage,
    /** 未通过核销的步数（"看起来做了但其实没生效"的次数） */
    val ineffectiveStepCount: Int
) {
    val stepCount: Int get() = steps.size
    val medianStepLatencyMs: Long get() {
        if (steps.isEmpty()) return 0L
        val sorted = steps.map { it.durationMs }.sorted()
        return sorted[sorted.size / 2]
    }
}

/**
 * 轨迹构建器：执行循环持有它逐步填充，任务终止时 [finish] 产出不可变轨迹。
 */
class ExecutionTraceBuilder(
    val taskId: String,
    val goal: String,
    val mode: ActionExecutionMode,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val startTimeMs: Long = timeProvider()
    private val steps = mutableListOf<TraceStep>()
    private var vlmCallCount: Int = 0
    private var selfHealAttempts: Int = 0
    private var interruptionCount: Int = 0
    private var macroHit: MacroHitRecord? = null
    private var tokenUsage: TokenUsage = TokenUsage()

    fun recordStep(step: TraceStep) {
        steps.add(step)
    }

    fun recordVlmCall() {
        vlmCallCount++
    }

    fun recordSelfHeal() {
        selfHealAttempts++
    }

    fun recordInterruption() {
        interruptionCount++
    }

    fun recordMacroHit(record: MacroHitRecord) {
        macroHit = record
    }

    fun recordTokenUsage(usage: TokenUsage) {
        tokenUsage += usage
    }

    fun finish(finalState: TraceFinalState, now: Long = timeProvider()): ExecutionTrace = ExecutionTrace(
        taskId = taskId,
        goal = goal,
        mode = mode,
        startTimeMs = startTimeMs,
        durationMs = (now - startTimeMs).coerceAtLeast(0L),
        finalState = finalState,
        steps = steps.toList(),
        vlmCallCount = vlmCallCount,
        selfHealAttempts = selfHealAttempts,
        interruptionCount = interruptionCount,
        macroHit = macroHit,
        tokenUsage = tokenUsage,
        ineffectiveStepCount = steps.count { it.verification == TraceStep.VerificationOutcome.NO_VISIBLE_EFFECT }
    )
}

/**
 * 三项核心指标聚合（方案 §九 第 2 条）。
 */
data class ExecutionMetrics(
    val totalTasks: Int,
    val successTasks: Int,
    val macroHitTasks: Int,
    val medianStepLatencyMs: Long,
    val averageStepsPerTask: Double
) {
    /** 单任务端到端成功率 */
    val successRate: Double get() = if (totalTasks == 0) 0.0 else successTasks.toDouble() / totalTasks

    /** Macro 命中率 */
    val macroHitRate: Double get() = if (totalTasks == 0) 0.0 else macroHitTasks.toDouble() / totalTasks
}

/**
 * 轨迹记录器：环形缓存最近 N 条轨迹 + 指标聚合。
 *
 * 环形缓存上限刻意与 LiricaLog 的 200 条对齐：诊断包导出时两者时间轴可直接对照。
 * [sink] 用于把轨迹同时写入 LiricaLog / DiagnosticExporter，异常被隔离，
 * 绝不允许可观测性组件反向击穿执行主链路。
 */
class ExecutionTraceRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val sink: (ExecutionTrace) -> Unit = {},
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val traces = ConcurrentLinkedDeque<ExecutionTrace>()
    private val stepLatencies = ConcurrentLinkedDeque<Long>()

    fun begin(goal: String, mode: ActionExecutionMode): ExecutionTraceBuilder =
        ExecutionTraceBuilder(
            taskId = UUID.randomUUID().toString(),
            goal = goal,
            mode = mode,
            timeProvider = timeProvider
        )

    fun complete(trace: ExecutionTrace) {
        traces.addLast(trace)
        while (traces.size > capacity) traces.pollFirst()
        trace.steps.forEach { stepLatencies.addLast(it.durationMs) }
        while (stepLatencies.size > capacity * 12) stepLatencies.pollFirst()
        runCatching { sink(trace) }
    }

    fun recentTraces(): List<ExecutionTrace> = traces.toList()

    fun latestTrace(): ExecutionTrace? = traces.peekLast()

    fun metrics(): ExecutionMetrics {
        val snapshot = traces.toList()
        val latencies = stepLatencies.toList().sorted()
        val median = if (latencies.isEmpty()) 0L else latencies[latencies.size / 2]
        val avgSteps = if (snapshot.isEmpty()) 0.0 else snapshot.sumOf { it.stepCount }.toDouble() / snapshot.size
        return ExecutionMetrics(
            totalTasks = snapshot.size,
            successTasks = snapshot.count { it.finalState == TraceFinalState.SUCCESS },
            macroHitTasks = snapshot.count { it.macroHit != null },
            medianStepLatencyMs = median,
            averageStepsPerTask = avgSteps
        )
    }

    fun clear() {
        traces.clear()
        stepLatencies.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
