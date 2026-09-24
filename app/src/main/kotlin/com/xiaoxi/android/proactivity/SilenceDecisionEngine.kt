package com.xiaoxi.android.proactivity

import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.perception.model.ScreenState

/**
 * 沉默裁决器与主动破冰门控中枢
 * 基于 CHI'25 Inner Thoughts 双层门控模型
 */
interface SilenceDecisionEngine {
    fun evaluate(
        curr: PerceptionSnapshot,
        innerMotivation: Double,
        lastInterruptionMs: Long,
        nowMs: Long,
        prev: PerceptionSnapshot? = null
    ): DecisionResult

    companion object {
        /** 内隐动机阈值 (低于此值 Lirica 完全静默，不生成内心独白) */
        const val IM_THRESHOLD = 3.6
        /** 打断阈值 (有效动机超过此值且情境代价适宜时，允许主动破冰) */
        const val INTERRUPT_THRESHOLD = 4.5
    }
}

class SilenceDecisionEngineImpl(
    private val estimator: InterruptibilityEstimator = InterruptibilityEstimatorImpl(),
    private val breakpointDetector: BreakpointDetector = BreakpointDetectorImpl()
) : SilenceDecisionEngine {

    override fun evaluate(
        curr: PerceptionSnapshot,
        innerMotivation: Double,
        lastInterruptionMs: Long,
        nowMs: Long,
        prev: PerceptionSnapshot?
    ): DecisionResult {
        val cost = estimator.estimateCost(curr, lastInterruptionMs, nowMs)
        val breakpoint = breakpointDetector.detect(prev, curr)

        // 1. 低于内隐动机阈值：完全沉默
        if (innerMotivation < SilenceDecisionEngine.IM_THRESHOLD) {
            return DecisionResult(
                action = ProactiveAction.SILENT,
                innerMotivation = innerMotivation,
                interruptCost = cost,
                effectiveMotivation = innerMotivation,
                breakpoint = breakpoint,
                reason = "内隐动机低于阈值 (${innerMotivation} < ${SilenceDecisionEngine.IM_THRESHOLD})"
            )
        }

        // 2. 物理与隐私硬防线：隐私熔断中或屏幕息屏时，严禁主动打扰，只允许内心独白
        if (curr.isPrivacyFusing) {
            return DecisionResult(
                action = ProactiveAction.INNER_THOUGHT_ONLY,
                innerMotivation = innerMotivation,
                interruptCost = cost,
                effectiveMotivation = innerMotivation,
                breakpoint = breakpoint,
                reason = "隐私保护熔断中，严格封禁显式打扰"
            )
        }

        if (curr.screenState.state == ScreenState.OFF) {
            return DecisionResult(
                action = ProactiveAction.INNER_THOUGHT_ONLY,
                innerMotivation = innerMotivation,
                interruptCost = cost,
                effectiveMotivation = innerMotivation,
                breakpoint = breakpoint,
                reason = "屏幕处于息屏待机状态，禁止唤起打扰气泡"
            )
        }

        // 3. 计算综合有效动机：有效动机 = (原始动机 * 断点增益) - (打扰代价折减)
        val effectiveMotivation = (innerMotivation * breakpoint.bonusFactor) - (cost - InterruptibilityEstimator.BASE_COST)

        // 4. 高打扰代价防线 (如游戏/会议等超高专注场景)
        if (cost >= 4.0) {
            return DecisionResult(
                action = ProactiveAction.INNER_THOUGHT_ONLY,
                innerMotivation = innerMotivation,
                interruptCost = cost,
                effectiveMotivation = effectiveMotivation,
                breakpoint = breakpoint,
                reason = "当前处于高专注/不可打扰状态 (Cost: $cost >= 4.0)"
            )
        }

        // 5. 双门控裁决：有效动机是否超越打断阈值
        return if (effectiveMotivation >= SilenceDecisionEngine.INTERRUPT_THRESHOLD) {
            DecisionResult(
                action = ProactiveAction.PROACTIVE_INTERRUPT,
                innerMotivation = innerMotivation,
                interruptCost = cost,
                effectiveMotivation = effectiveMotivation,
                breakpoint = breakpoint,
                reason = "有效动机超越打断阈值 ($effectiveMotivation >= ${SilenceDecisionEngine.INTERRUPT_THRESHOLD})"
            )
        } else {
            DecisionResult(
                action = ProactiveAction.INNER_THOUGHT_ONLY,
                innerMotivation = innerMotivation,
                interruptCost = cost,
                effectiveMotivation = effectiveMotivation,
                breakpoint = breakpoint,
                reason = "有效动机未达打断阈值 ($effectiveMotivation < ${SilenceDecisionEngine.INTERRUPT_THRESHOLD})"
            )
        }
    }
}
