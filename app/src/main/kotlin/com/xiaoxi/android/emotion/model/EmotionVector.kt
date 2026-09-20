package com.xiaoxi.android.emotion.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 连续情绪空间向量（Russell 2D 环形模型 + PAD 支配度扩展）
 *
 * @param valence 情绪效价 [-1.0, 1.0]，负向 (悲伤/不悦) ↔ 正向 (愉悦/欣喜)
 * @param arousal 唤醒度 [-1.0, 1.0]，低唤醒 (困倦/沉静) ↔ 高唤醒 (激动/警觉)
 * @param dominance 支配度 [-1.0, 1.0]，低支配 (无助/受制) ↔ 高支配 (掌控/坚决)，默认为 0.0
 */
data class EmotionVector(
    val valence: Float,
    val arousal: Float,
    val dominance: Float = 0.0f
) {
    /**
     * 严格限制坐标在 [-1.0, 1.0] 的物理盒内，且免疫 NaN 异常值
     */
    fun clamped(): EmotionVector {
        val safeV = if (valence.isNaN()) 0.0f else max(-1.0f, min(1.0f, valence))
        val safeA = if (arousal.isNaN()) 0.0f else max(-1.0f, min(1.0f, arousal))
        val safeD = if (dominance.isNaN()) 0.0f else max(-1.0f, min(1.0f, dominance))
        return EmotionVector(
            valence = safeV,
            arousal = safeA,
            dominance = safeD
        )
    }

    /**
     * 计算到另一个向量在 2D (Valence, Arousal) 平面上的欧式距离
     */
    fun distance2D(other: EmotionVector): Float {
        val dv = valence - other.valence
        val da = arousal - other.arousal
        return sqrt(dv * dv + da * da)
    }

    /**
     * 向量加法
     */
    operator fun plus(other: EmotionVector): EmotionVector {
        return EmotionVector(
            valence = valence + other.valence,
            arousal = arousal + other.arousal,
            dominance = dominance + other.dominance
        )
    }

    /**
     * 向量减法
     */
    operator fun minus(other: EmotionVector): EmotionVector {
        return EmotionVector(
            valence = valence - other.valence,
            arousal = arousal - other.arousal,
            dominance = dominance - other.dominance
        )
    }

    /**
     * 标量乘法
     */
    operator fun times(scalar: Float): EmotionVector {
        return EmotionVector(
            valence = valence * scalar,
            arousal = arousal * scalar,
            dominance = dominance * scalar
        )
    }
}
