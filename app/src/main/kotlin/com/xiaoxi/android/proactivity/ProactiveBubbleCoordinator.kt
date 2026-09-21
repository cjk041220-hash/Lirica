package com.xiaoxi.android.proactivity

import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 主动表达级别映射
 * 严格封禁任何全屏阻塞式弹窗 (L4 严厉禁止)
 */
enum class ProactivityLevel {
    /** L0: 静默更新，仅更新内部动力学与感知状态，无任何显式呈现 */
    L0_SILENT,
    /** L1: 伴生体微表情/呼吸光效微调，表达内心活动，无气泡打扰 */
    L1_MICRO_EXPRESSION,
    /** L2: 伴生体头顶非阻塞破冰小气泡 (IcebreakerBubble)，可一键对话或划掉 */
    L2_ICEBREAKER_BUBBLE,
    /** L3: 轻提示通知 (仅在极高紧急度通知且屏幕关闭等特殊边界下使用) */
    L3_LIGHT_NOTIFICATION
}

/**
 * 用户对主动破冰气泡的反馈类型
 */
enum class UserFeedback {
    /** 用户点击气泡接茬，进入对话面板 (正面反馈) */
    CLICKED,
    /** 用户主动点击关闭或划掉气泡 (强负面反馈) */
    DISMISSED,
    /** 气泡超时自然消失，用户未做交互 (弱负面反馈) */
    IGNORED
}

/**
 * 主动协调中枢裁决结果
 */
data class ProactiveCoordinationResult(
    val level: ProactivityLevel,
    val decision: DecisionResult,
    val thought: UnspokenThoughtEntity?,
    val reason: String,
    val bubblesShownToday: Int
)

/**
 * 悬浮窗主动破冰气泡与防打扰退避总线协调器
 */
interface ProactiveBubbleCoordinator {
    suspend fun coordinate(snapshot: PerceptionSnapshot, nowMs: Long = System.currentTimeMillis()): ProactiveCoordinationResult
    suspend fun onUserFeedback(feedback: UserFeedback, thoughtId: String?, nowMs: Long = System.currentTimeMillis())
    fun getBackoffPenalty(): Double
    fun getBubblesShownToday(): Int

    companion object {
        /** 每日主动破冰气泡配额硬上限 (2次/天) */
        const val DAILY_BUBBLE_QUOTA = 2
    }
}

class ProactiveBubbleCoordinatorImpl(
    private val thoughtManager: UnspokenThoughtManager,
    private val decisionEngine: SilenceDecisionEngine,
    private val emotionEngine: EmotionEngine
) : ProactiveBubbleCoordinator {

    private var bubblesShownToday = 0
    private var lastBubbleDay: String = ""
    private var lastInterruptionTime = 0L
    private var backoffPenalty = 0.0
    private var prevSnapshot: PerceptionSnapshot? = null

    override fun getBackoffPenalty(): Double = backoffPenalty
    override fun getBubblesShownToday(): Int = bubblesShownToday

    override suspend fun coordinate(
        snapshot: PerceptionSnapshot,
        nowMs: Long
    ): ProactiveCoordinationResult {
        // 1. 日期跨天重置配额
        val currentDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(nowMs))
        if (currentDay != lastBubbleDay) {
            lastBubbleDay = currentDay
            bubblesShownToday = 0
            // 跨天退避惩罚衰减一半
            backoffPenalty = max(0.0, backoffPenalty * 0.5)
        }

        // 2. 动机动力学计算与念头更新 (融合即时感知动力学与待决念头池最高紧急度)
        val perceptionMotivation = thoughtManager.processPerception(snapshot, emotionEngine.currentVector, nowMs)
        val topPendingThought = thoughtManager.getTopPendingThoughts(limit = 1).firstOrNull()
        val thoughtUrgency = topPendingThought?.urgency?.toDouble() ?: 0.0
        val rawMotivation = max(perceptionMotivation, thoughtUrgency)

        // 3. 用户退避惩罚抵扣
        val effectiveInnerMotivation = max(0.0, rawMotivation - backoffPenalty)

        // 4. 沉默裁决器双门控判定
        val decision = decisionEngine.evaluate(
            curr = snapshot,
            innerMotivation = effectiveInnerMotivation,
            lastInterruptionMs = lastInterruptionTime,
            nowMs = nowMs,
            prev = prevSnapshot
        )
        prevSnapshot = snapshot

        // 5. 分级映射与每日配额守门
        return when (decision.action) {
            ProactiveAction.SILENT -> {
                ProactiveCoordinationResult(
                    level = ProactivityLevel.L0_SILENT,
                    decision = decision,
                    thought = null,
                    reason = decision.reason,
                    bubblesShownToday = bubblesShownToday
                )
            }
            ProactiveAction.INNER_THOUGHT_ONLY -> {
                // 微表情微调光晕刺激
                emotionEngine.applyStimulus(EmotionVector(valence = 0.05f, arousal = 0.05f))
                ProactiveCoordinationResult(
                    level = ProactivityLevel.L1_MICRO_EXPRESSION,
                    decision = decision,
                    thought = null,
                    reason = decision.reason,
                    bubblesShownToday = bubblesShownToday
                )
            }
            ProactiveAction.PROACTIVE_INTERRUPT -> {
                val topThoughts = thoughtManager.getTopPendingThoughts(limit = 1)
                val topThought = topThoughts.firstOrNull()
                val isPriorityWakeThought = topThought != null && (
                    topThought.id.startsWith("thought_wake_") || topThought.urgency >= 4.5f
                )

                // 每日配额硬约束守门 (高优先级自定约定闹钟念头跳过闲聊配额限制)
                if (bubblesShownToday >= ProactiveBubbleCoordinator.DAILY_BUBBLE_QUOTA && !isPriorityWakeThought) {
                    ProactiveCoordinationResult(
                        level = ProactivityLevel.L1_MICRO_EXPRESSION,
                        decision = decision,
                        thought = null,
                        reason = "每日主动破冰配额已用尽 (${bubblesShownToday}/${ProactiveBubbleCoordinator.DAILY_BUBBLE_QUOTA})，降级为伴生微表情",
                        bubblesShownToday = bubblesShownToday
                    )
                } else {
                    if (topThought != null) {
                        if (!isPriorityWakeThought) {
                            bubblesShownToday++
                        }
                        lastInterruptionTime = nowMs
                        ProactiveCoordinationResult(
                            level = ProactivityLevel.L2_ICEBREAKER_BUBBLE,
                            decision = decision,
                            thought = topThought,
                            reason = if (isPriorityWakeThought) "自定闹钟约定到期唤醒，优先展示履约气泡" else "触发主动破冰条件，展示头顶内心独白气泡",
                            bubblesShownToday = bubblesShownToday
                        )
                    } else {
                        ProactiveCoordinationResult(
                            level = ProactivityLevel.L1_MICRO_EXPRESSION,
                            decision = decision,
                            thought = null,
                            reason = "虽达成打断阈值但念头池无待表达念头，降级为微表情",
                            bubblesShownToday = bubblesShownToday
                        )
                    }
                }
            }
        }
    }

    override suspend fun onUserFeedback(
        feedback: UserFeedback,
        thoughtId: String?,
        nowMs: Long
    ) {
        when (feedback) {
            UserFeedback.CLICKED -> {
                // 正向反馈：消耗念头，标记已表达，减轻退避惩罚
                if (thoughtId != null) {
                    thoughtManager.consumeThought(thoughtId, nowMs)
                }
                thoughtManager.markExpressed(nowMs)
                backoffPenalty = max(0.0, backoffPenalty - 0.5)
            }
            UserFeedback.DISMISSED -> {
                // 强负向反馈：关闭念头，记录冷却，显著加重退避惩罚
                if (thoughtId != null) {
                    thoughtManager.consumeThought(thoughtId, nowMs)
                }
                thoughtManager.markExpressed(nowMs)
                lastInterruptionTime = nowMs
                backoffPenalty = min(3.0, backoffPenalty + 1.0)
            }
            UserFeedback.IGNORED -> {
                // 弱负向反馈：轻微加重退避惩罚
                lastInterruptionTime = nowMs
                backoffPenalty = min(3.0, backoffPenalty + 0.3)
            }
        }
    }
}
