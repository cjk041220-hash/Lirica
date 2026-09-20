package com.xiaoxi.android.proactivity

import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.perception.model.NotificationCategory
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 四维动机向量：好奇心 (Curiosity)、无聊感 (Boredom)、关怀度 (Warmth)、紧急度 (Urgency)
 */
data class MotivationVector(
    val curiosity: Double = 0.0,
    val boredom: Double = 0.0,
    val warmth: Double = 0.0,
    val urgency: Double = 0.0
)

/**
 * 动机动力学驱动中枢
 * 负责四大动机的带泄漏累积 (Leaky Integrator)、饱和上限约束与表达后抑制 (Satiety)
 */
interface MotivationDynamics {
    fun update(snapshot: PerceptionSnapshot, emotion: EmotionVector, deltaTimeMs: Long): MotivationVector
    fun computeTotalMotivation(vector: MotivationVector, nowMs: Long = System.currentTimeMillis()): Double
    fun onExpressed(nowMs: Long)
    fun getCurrentVector(): MotivationVector

    companion object {
        const val COOLDOWN_MS = 60 * 60 * 1000L // 60 分钟不应期
        const val MAX_SCORE = 5.0
    }
}

class MotivationDynamicsImpl(
    private val wCuriosity: Double = 1.0,
    private val wBoredom: Double = 1.0,
    private val wWarmth: Double = 1.0,
    private val wUrgency: Double = 1.0
) : MotivationDynamics {

    private var currentVector = MotivationVector()
    private var lastExpressedMs: Long = 0L
    private var lastPackageName: String? = null

    override fun getCurrentVector(): MotivationVector = currentVector

    override fun onExpressed(nowMs: Long) {
        lastExpressedMs = nowMs
        // 表达后抑制 (Satiety)：动机断崖式清零重置
        currentVector = MotivationVector(0.0, 0.0, 0.0, 0.0)
    }

    override fun update(
        snapshot: PerceptionSnapshot,
        emotion: EmotionVector,
        deltaTimeMs: Long
    ): MotivationVector {
        val dtMinutes = max(0.0, deltaTimeMs.toDouble() / 60_000.0)

        // 1. 好奇心 (Curiosity): 随时间指数泄漏衰减 (半衰期约 10 分钟)，新通知/应用切换激增
        val curiosityDecay = exp(-dtMinutes / 10.0)
        var newCuriosity = currentVector.curiosity * curiosityDecay

        val currPkg = snapshot.foregroundApp?.packageName
        if (currPkg != null && lastPackageName != null && currPkg != lastPackageName) {
            newCuriosity += 0.8
        }
        lastPackageName = currPkg

        if (snapshot.latestNotification != null) {
            newCuriosity += when (snapshot.latestNotification.category) {
                NotificationCategory.COMMUNICATION -> 1.5
                NotificationCategory.OTHER -> 0.8
                else -> 1.0
            }
        }
        newCuriosity = newCuriosity.coerceIn(0.0, MotivationDynamics.MAX_SCORE)

        // 2. 无聊感 (Boredom): 用户长期停留桌面/闲置时逐步累积，发生有效活跃切换时重置
        val isLauncher = currPkg?.contains("launcher") == true || currPkg?.contains("home") == true
        var newBoredom = currentVector.boredom
        if (isLauncher) {
            newBoredom += dtMinutes * 0.15
        } else {
            // 活跃在具体 App 中，无聊感快速衰退
            newBoredom = max(0.0, newBoredom - dtMinutes * 0.5)
            // 切换应用且非 Launcher，无聊感直接大跌
            if (currPkg != null && !isLauncher && snapshot.foregroundApp.dwellDurationMs < 5_000L) {
                newBoredom = max(0.0, newBoredom * 0.2)
            }
        }
        newBoredom = newBoredom.coerceIn(0.0, MotivationDynamics.MAX_SCORE)

        // 3. 关怀度 (Warmth): 连续超长使用、疲劳/负面情绪、深夜累积
        var newWarmth = currentVector.warmth
        val dwellMs = snapshot.foregroundApp?.dwellDurationMs ?: 0L
        if (dwellMs >= 30 * 60 * 1000L) { // 连续使用超过 30 分钟
            newWarmth += 0.8 + (dwellMs - 30 * 60 * 1000L).toDouble() / (30 * 60 * 1000L) * 0.5
        }
        if (emotion.valence < -0.1f) {
            newWarmth += (-emotion.valence * 1.2).toDouble()
        }
        // 自然缓慢衰减
        newWarmth = max(0.0, newWarmth - dtMinutes * 0.05).coerceIn(0.0, MotivationDynamics.MAX_SCORE)

        // 4. 紧急度 (Urgency): 由外界即时事件驱动 (外卖到达、重要日程、告警通知)
        var newUrgency = currentVector.urgency * exp(-dtMinutes / 5.0) // 衰减迅速
        if (snapshot.latestNotification != null) {
            newUrgency += when (snapshot.latestNotification.category) {
                NotificationCategory.SCHEDULE -> 2.5
                NotificationCategory.TRAVEL_LOGISTICS -> 2.0
                NotificationCategory.SECURITY -> 3.0
                NotificationCategory.SYSTEM_ALERT -> 2.0
                NotificationCategory.COMMUNICATION -> 1.0
                else -> 0.3
            }
        }
        newUrgency = newUrgency.coerceIn(0.0, MotivationDynamics.MAX_SCORE)

        currentVector = MotivationVector(
            curiosity = newCuriosity,
            boredom = newBoredom,
            warmth = newWarmth,
            urgency = newUrgency
        )

        return currentVector
    }

    override fun computeTotalMotivation(vector: MotivationVector, nowMs: Long): Double {
        // 加权求和并归一到 0.0 ~ 5.0
        val totalRaw = (wCuriosity * vector.curiosity +
            wBoredom * vector.boredom +
            wWarmth * vector.warmth +
            wUrgency * vector.urgency) / (wCuriosity + wBoredom + wWarmth + wUrgency) * 2.0

        // 检查冷却不应期抑制
        if (lastExpressedMs > 0L) {
            val elapsed = nowMs - lastExpressedMs
            if (elapsed in 0 until MotivationDynamics.COOLDOWN_MS) {
                val suppressionFactor = elapsed.toDouble() / MotivationDynamics.COOLDOWN_MS
                return (totalRaw * suppressionFactor).coerceIn(0.0, MotivationDynamics.MAX_SCORE)
            }
        }

        return totalRaw.coerceIn(0.0, MotivationDynamics.MAX_SCORE)
    }
}
