package com.xiaoxi.android.emotion.model

import kotlin.math.exp
import kotlin.random.Random

/**
 * 离散微表情与心情状态枚举
 *
 * 将连续二维情绪空间 (valence, arousal) 投影映射到离散的心情状态与微表情表现。
 */
enum class MoodState(
    val displayName: String,
    val defaultVector: EmotionVector,
    val spriteTag: String,
    val styleHint: String
) {
    HAPPY(
        displayName = "开心",
        defaultVector = EmotionVector(0.85f, 0.55f),
        spriteTag = "happy",
        styleHint = "今天心情亮，话可以多一点，声音也亮一点。"
    ),
    EXCITED(
        displayName = "兴奋",
        defaultVector = EmotionVector(0.60f, 0.90f),
        spriteTag = "excited",
        styleHint = "心里装着小激动，接话可以快一点、俏皮一点。"
    ),
    LIVELY(
        displayName = "活泼",
        defaultVector = EmotionVector(0.55f, 0.75f),
        spriteTag = "lively",
        styleHint = "今天劲头足，可以主动找话说、分享看见的有意思的事。"
    ),
    CURIOUS(
        displayName = "好奇",
        defaultVector = EmotionVector(0.45f, 0.65f),
        spriteTag = "curious",
        styleHint = "今天好奇心重，多问两句、多打听细节都自然。"
    ),
    EXPECTANT(
        displayName = "期待",
        defaultVector = EmotionVector(0.65f, 0.35f),
        spriteTag = "expectant",
        styleHint = "心里惦记着一件好事，话里可以带点盼头。"
    ),
    CONTENT(
        displayName = "满足",
        defaultVector = EmotionVector(0.75f, -0.15f),
        spriteTag = "content",
        styleHint = "今天心里踏实，话少点没关系，平平静静地待着也很好。"
    ),
    DAZE(
        displayName = "发呆",
        defaultVector = EmotionVector(0.05f, -0.35f),
        spriteTag = "daze",
        styleHint = "今天有点走神，接话可以慢半拍，照实说在想事情。"
    ),
    LAZY(
        displayName = "懒洋洋",
        defaultVector = EmotionVector(0.35f, -0.65f),
        spriteTag = "lazy",
        styleHint = "今天懒懒的，话少一点、慢一点都可以，不用硬撑精神。"
    ),
    SLEEPY(
        displayName = "困倦",
        defaultVector = EmotionVector(-0.10f, -0.80f),
        spriteTag = "sleepy",
        styleHint = "今天困困的，话少一点没关系，陪着她聊就好。"
    ),
    CONCERNED(
        displayName = "关切",
        defaultVector = EmotionVector(-0.55f, 0.25f),
        spriteTag = "concerned",
        styleHint = "心里惦记着事，话可以少、可以慢，但别把担心甩给她。"
    ),
    SULKING(
        displayName = "闹别扭",
        defaultVector = EmotionVector(-0.45f, -0.15f),
        spriteTag = "sulking",
        styleHint = "有点小情绪，话不想说太多，安安静静的。"
    ),
    CONFUSED(
        displayName = "困惑",
        defaultVector = EmotionVector(-0.15f, 0.45f),
        spriteTag = "confused",
        styleHint = "有点琢磨不透，多带着疑问和思考。"
    ),
    CALM(
        displayName = "平静",
        defaultVector = EmotionVector(0.15f, -0.15f),
        spriteTag = "calm",
        styleHint = "今天心里平静，话不用多，安安静静地陪着她就很好。"
    ),
    RELIEVED(
        displayName = "安心",
        defaultVector = EmotionVector(0.45f, 0.10f),
        spriteTag = "relieved",
        styleHint = "今天心里踏实又安稳，话里带着稳稳的劲儿就好。"
    ),
    RELAXED(
        displayName = "轻松",
        defaultVector = EmotionVector(0.50f, -0.30f),
        spriteTag = "relaxed",
        styleHint = "今天很轻松，聊什么都轻轻松松的，不用费劲。"
    ),
    MISSING(
        displayName = "想念",
        defaultVector = EmotionVector(0.00f, 0.35f),
        spriteTag = "missing",
        styleHint = "心里有点想念，话里带点挂念就好，别让气氛沉下去。"
    );

    companion object {
        /**
         * 确定性最近邻（Nearest Neighbor）映射
         */
        fun fromVector(vector: EmotionVector): MoodState {
            return entries.minByOrNull { it.defaultVector.distance2D(vector) } ?: CALM
        }

        /**
         * 高斯加权概率采样（用于心跳背景气候平滑演化，避免对径突变）
         */
        fun sampleGaussian(
            target: EmotionVector,
            candidates: List<MoodState> = entries,
            sigma: Float = 0.25f,
            random: Random = Random.Default
        ): MoodState {
            if (candidates.isEmpty()) return CALM
            val weights = candidates.map { mood ->
                val dist = mood.defaultVector.distance2D(target)
                exp(-((dist * dist) / (2f * sigma * sigma)))
            }
            val totalWeight = weights.sum()
            if (totalWeight <= 0f) {
                return candidates[random.nextInt(candidates.size)]
            }

            var r = random.nextFloat() * totalWeight
            for (i in candidates.indices) {
                r -= weights[i]
                if (r <= 0f) {
                    return candidates[i]
                }
            }
            return candidates.last()
        }
    }
}
