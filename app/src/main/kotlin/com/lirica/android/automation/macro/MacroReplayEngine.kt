package com.lirica.android.automation.macro

import com.lirica.android.automation.verify.PageFingerprint

/**
 * 单步回放执行结果（由闭环侧的核销器产出）。
 */
data class MacroStepExecution(
    val success: Boolean,
    val reason: String? = null,
    val blockedRule: String? = null,
    /**
     * 单步轻量核销是否通过（§5.2 第 2 条）。
     * 派发成功但页面无变化时为 false —— 这正是"回放看着在动、实际没生效"的检出点。
     */
    val verified: Boolean = true
)

/**
 * 单步回放执行器契约。
 *
 * 刻意用函数式接口而不是直接依赖 Coordinator：回放引擎需要的是
 * "把一步宏动作打到屏幕上并告诉我有没有生效"，而不是整个执行引擎。
 * 这样回放纪律（起始页核验、逐步核销、失败降级）能在纯 JVM 单测里穷举。
 */
fun interface MacroStepExecutor {
    suspend fun execute(step: MacroStep, resolvedText: String?, stepIndex: Int): MacroStepExecution
}

/** 起始页核验器契约（默认严格相等） */
fun interface StartPageVerifier {
    fun matches(expectedFingerprint: Long, actual: PageFingerprint?): Boolean

    companion object {
        /**
         * 严格核验：指纹必须存在、有效且完全相等。
         *
         * 为什么不做"相似度容差"：§5.2 的第一条纪律是"起始页防暴走"。
         * 容差会让"其实已经在别的页面"被误判为可回放，
         * 回放序列会打在完全无关的界面上——这是比失败更糟的结果。
         */
        val STRICT = StartPageVerifier { expected, actual ->
            actual != null && actual.isValid && actual.hash == expected
        }
    }
}

/** 回放结论 */
sealed interface MacroReplayOutcome {
    data class Success(val macroId: String, val steps: Int) : MacroReplayOutcome

    data class NotReplayable(val reason: String) : MacroReplayOutcome

    data class StartPageMismatch(val expected: Long, val actual: Long?, val reason: String) : MacroReplayOutcome

    data class MissingSlots(val missingKeys: List<String>) : MacroReplayOutcome

    data class StepFailed(val macroId: String, val stepIndex: Int, val reason: String) : MacroReplayOutcome

    data class Blocked(val macroId: String, val stepIndex: Int, val rule: String, val reason: String) : MacroReplayOutcome
}

/**
 * 自适应回放引擎（M5.3 §5.2「核验前置、逐步验证、版本淘汰」铁律）。
 *
 * 三条纪律的落地位置：
 * 1. **核验前置**：[replay] 的第一步就是起始页指纹核验，不匹配直接返回
 *    [MacroReplayOutcome.StartPageMismatch]，绝不"先点一下看看"；
 * 2. **逐步验证**：每一步都要求 [MacroStepExecution.verified] 为真，
 *    任一步未生效立即中止回放（调用方据此平滑降级为动态 ReAct 循环）；
 * 3. **版本淘汰**：版本维度在 TrajectoryMemory 的查询层排除（DB 查询条件），
 *    不依赖运行时判断，避免"查出来才发现版本不对"的白跑一趟。
 *
 * 本类不含任何 Android 依赖，纯逻辑，可被完全穷举测试。
 */
class MacroReplayEngine(
    private val startPageVerifier: StartPageVerifier = StartPageVerifier.STRICT
) {

    suspend fun replay(
        macro: ActionMacro,
        slots: Map<String, String>,
        currentPage: PageFingerprint?,
        /**
         * 单任务步数硬顶（§1.2.3 Bounded-Loop Invariant）。
         *
         * 回放同样受"12 步原子循环"约束：宏不是绕过有界性的后门——
         * 一条录了 30 步的宏如果整段回放，等于把方案写死的上限直接作废。
         */
        maxSteps: Int = Int.MAX_VALUE,
        executor: MacroStepExecutor
    ): MacroReplayOutcome {
        if (macro.steps.size > maxSteps) {
            return MacroReplayOutcome.NotReplayable(
                "宏步数 " + macro.steps.size + " 超过单任务步数上限 " + maxSteps + "，拒绝整段回放"
            )
        }
        if (!macro.isReplayable) {
            return MacroReplayOutcome.NotReplayable(
                "宏结构不合法或已弃用（steps=" + macro.steps.size + ", deprecated=" + macro.isDeprecated + "）"
            )
        }
        if (macro.startPageFingerprint == 0L) {
            // 起始页指纹为 0 意味着录制时就没采到，回放将无参照 —— 直接拒绝，拒绝盲回放
            return MacroReplayOutcome.NotReplayable("宏缺少起始页指纹，拒绝盲回放")
        }

        val missingSlots = macro.explicitSlotKeys.filter { !slots.containsKey(it) }
        if (missingSlots.isNotEmpty()) {
            return MacroReplayOutcome.MissingSlots(missingSlots)
        }

        if (!startPageVerifier.matches(macro.startPageFingerprint, currentPage)) {
            return MacroReplayOutcome.StartPageMismatch(
                expected = macro.startPageFingerprint,
                actual = currentPage?.hash,
                reason = if (currentPage == null) {
                    "当前页面指纹不可采样"
                } else {
                    "当前页面指纹 " + currentPage.hash + " 与录制起始页 " + macro.startPageFingerprint + " 不一致"
                }
            )
        }

        macro.steps.forEachIndexed { index, step ->
            val resolvedText = step.resolveText(slots)
            if (step.usesSlot && resolvedText == null) {
                return MacroReplayOutcome.StepFailed(macro.macroId, index, "缺少显式槽位：" + step.slotKey)
            }
            val execution = executor.execute(step, resolvedText, index)
            if (!execution.success) {
                return if (execution.blockedRule != null) {
                    MacroReplayOutcome.Blocked(
                        macroId = macro.macroId,
                        stepIndex = index,
                        rule = execution.blockedRule,
                        reason = execution.reason ?: "安全闸门拦截"
                    )
                } else {
                    MacroReplayOutcome.StepFailed(
                        macroId = macro.macroId,
                        stepIndex = index,
                        reason = execution.reason ?: "未生效"
                    )
                }
            }
            if (!execution.verified) {
                return MacroReplayOutcome.StepFailed(macro.macroId, index, "单步核销未通过")
            }
        }

        return MacroReplayOutcome.Success(macro.macroId, macro.steps.size)
    }
}
