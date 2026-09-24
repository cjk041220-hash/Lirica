package com.xiaoxi.android.database.memory

import com.xiaoxi.android.database.entity.UserMemoryEntity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * 记忆相关性与多维认知加权评分器 (Memory Scorer)
 *
 * 严格继承 Lirica 沉淀与《AI Agent 记忆架构调研》4.2 节核心公式：
 * S(m, q) = S_recency + S_access + S_usage + S_importance + S_topic + S_mood
 *
 * 1. 时间衰减 (S_recency)：基于统一提及时钟 (last_mention_ts or created_at)，半衰期 ≈ 7 天
 * 2. 访问热度 (S_access)：对数增强 ln(1 + access_count) * 0.15，防止老热门垄断
 * 3. 最近使用 (S_usage)：last_used_at 半衰期 ≈ 3.5 天，加权 0.3
 * 4. 重要度 (S_importance)：importance (1~10) * 0.03
 * 5. 话题重合度 (S_topic)：字符 2-gram overlap，权重 1.0 (层内细排，兼顾中文与短文本)
 * 6. 情绪亲和度 (S_mood)：mood-congruent 软调制 (0.15 * affinity)，效价相近加权，中性免疫
 */
object MemoryScorer {

    const val MOOD_AFFINITY_WEIGHT = 0.15f
    const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * 计算字符 2-gram 集合（去除所有空白符）
     * 适用于中英文字符串，无需庞大分词依赖即可捕获短文本指纹
     */
    fun charBigrams(text: String): Set<String> {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.length < 2) {
            return if (clean.isNotEmpty()) setOf(clean) else emptySet()
        }
        val set = mutableSetOf<String>()
        for (i in 0 until clean.length - 1) {
            set.add(clean.substring(i, i + 2))
        }
        return set
    }

    /**
     * 计算两段文本的字符 2-gram 重叠系数 (Overlap Coefficient)
     * 分母采用 min(|A|, |B|)，防止长内容稀释短 query
     */
    fun bigramOverlap(query: String, content: String): Float {
        val a = charBigrams(query)
        val b = charBigrams(content)
        if (a.isEmpty() || b.isEmpty()) return 0.0f
        val intersectionSize = a.intersect(b).size
        val minDenominator = min(a.size, b.size).toFloat()
        return if (minDenominator > 0f) intersectionSize / minDenominator else 0.0f
    }

    /**
     * 计算情绪亲和度 [0.0, 1.0] (Bower 1981 Mood-Congruent Memory)
     *
     * - moodValence == null: 因子关闭，返回 0.0f
     * - memoryValence == 0.0f: 中性免疫，恒定返回 0.5f，不吃偏置
     * - 其他: 1.0 - |v_mem - v_mood| / 2.0
     */
    fun moodAffinity(memoryValence: Float, moodValence: Float?): Float {
        if (moodValence == null) return 0.0f
        val safeMemV = if (memoryValence.isNaN()) 0.0f else max(-1.0f, min(1.0f, memoryValence))
        if (safeMemV == 0.0f) return 0.5f

        val safeMoodV = if (moodValence.isNaN()) 0.0f else max(-1.0f, min(1.0f, moodValence))
        val diff = abs(safeMemV - safeMoodV)
        return (1.0f - diff / 2.0f).coerceIn(0.0f, 1.0f)
    }

    /**
     * 计算综合相关性得分
     */
    fun calculateScore(
        memory: UserMemoryEntity,
        query: String = "",
        nowTs: Long = System.currentTimeMillis(),
        moodValence: Float? = null
    ): Float {
        // 1. 时间衰减 (半衰期 ≈ 7 天)
        val lastMention = if (memory.lastMentionTs > 0L) memory.lastMentionTs else memory.createdAt
        val daysOld = max(0.0, (nowTs - lastMention).toDouble() / MILLIS_PER_DAY)
        val recency = exp(-0.1 * daysOld).toFloat()

        // 2. 访问热度 (对数饱和)
        val accessBoost = (ln1p(max(0, memory.accessCount).toDouble()) * 0.15).toFloat()

        // 3. 最近使用 (半衰期 ≈ 3.5 天)
        val usageRecency = if (memory.lastUsedAt != null && memory.lastUsedAt > 0L) {
            val usedDays = max(0.0, (nowTs - memory.lastUsedAt).toDouble() / MILLIS_PER_DAY)
            (exp(-0.2 * usedDays) * 0.3).toFloat()
        } else {
            0.0f
        }

        // 4. 重要性加权 (importance 1..10 -> 0.03..0.30)
        val clampedImportance = memory.importance.coerceIn(1, 10)
        val importanceBoost = clampedImportance * 0.03f

        // 5. 话题匹配加分 (bigram overlap)
        val topicScore = if (query.isNotBlank()) {
            val haystack = "${memory.content} ${memory.category}"
            bigramOverlap(query, haystack)
        } else {
            0.0f
        }

        // 6. 情绪亲和加权
        val moodScore = MOOD_AFFINITY_WEIGHT * moodAffinity(memory.valence, moodValence)

        return recency + accessBoost + usageRecency + importanceBoost + topicScore + moodScore
    }
}
