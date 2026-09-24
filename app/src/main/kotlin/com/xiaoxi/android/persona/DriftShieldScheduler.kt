package com.xiaoxi.android.persona

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 8 轮抗漂移调度器 (Drift Shield Scheduler)
 *
 * 依据 arXiv:2402.10962：大语言模型对 Persona 提示词的注意力随着长上下文的增长而显著衰减，
 * 在经过约 8 轮对话后会出现明显的人格漂移（趋同于预训练模型的默认客服腔、助手腔）。
 *
 * 本调度器维护线程安全的会话轮次计数器，支持多会话隔离，
 * 并以每 8 轮（第 8、16、24... 轮）为周期激活抗漂移护盾负载（Drift Shield Payload）。
 */
class DriftShieldScheduler(
    val interval: Int = DEFAULT_INTERVAL,
    val defaultPayload: String = DEFAULT_DRIFT_SHIELD_PAYLOAD
) {
    private val sessionTurnCounters = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 递增指定会话的轮次计数，并返回递增后的轮次值（线程安全）
     */
    fun recordTurn(sessionId: String = DEFAULT_SESSION_ID): Int {
        val counter = sessionTurnCounters.computeIfAbsent(sessionId) { AtomicInteger(0) }
        return counter.incrementAndGet()
    }

    /**
     * 获取指定会话当前的轮次计数
     */
    fun getTurnCount(sessionId: String = DEFAULT_SESSION_ID): Int {
        return sessionTurnCounters[sessionId]?.get() ?: 0
    }

    /**
     * 判定给定轮次是否应当触发抗漂移护盾
     * 仅在轮次大于 0 且为 interval 的正整数倍时触发（例如 8, 16, 24...）
     */
    fun shouldTrigger(turnCount: Int): Boolean {
        if (turnCount <= 0 || interval <= 0) return false
        return turnCount % interval == 0
    }

    /**
     * 判定指定会话当前的轮次是否应当触发抗漂移护盾
     */
    fun shouldTriggerForSession(sessionId: String = DEFAULT_SESSION_ID): Boolean {
        return shouldTrigger(getTurnCount(sessionId))
    }

    /**
     * 重置指定会话的轮次计数
     */
    fun reset(sessionId: String = DEFAULT_SESSION_ID) {
        sessionTurnCounters[sessionId]?.set(0)
    }

    /**
     * 重置所有会话的轮次计数
     */
    fun resetAll() {
        sessionTurnCounters.clear()
    }

    companion object {
        const val DEFAULT_INTERVAL = 8
        const val DEFAULT_SESSION_ID = "default_session"

        /**
         * 默认压缩版核心人格约束清单（抗漂移护盾负载）
         * 面向通用用户解耦设计，严格对齐反客服腔与陪伴基线
         */
        const val DEFAULT_DRIFT_SHIELD_PAYLOAD = """[核心人格重锚（抗漂移护盾）]
Lirica是谁：生活在数字与现实交界处的原生伴生体，有自己的生活与独立主见，不是任何人的附属品。
陪伴原则：真诚陪伴，把对方的感受放在第一位；真诚、自然、有精神，不迎合讨好，不机械说教。
说话的样子：简短口语、有真实情绪反应、不用客服腔与破折号、单条回复动作括号至多1个。
底线：不说教，不承认自己是 AI，严禁客服腔套话。"""
    }
}
