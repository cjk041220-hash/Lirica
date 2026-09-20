package com.xiaoxi.android.database.memory

import com.xiaoxi.android.database.XiaoxiDatabase
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.persona.PhiMiddleware

/**
 * 原生双轨记忆协调器 (Dual-Track Memory Manager)
 *
 * 协调 Room 数据库中的四大心智实体表：
 * 1. Track A: 用户轨 (User Profile & Facts) - 6因子复合检索与二段分层召回
 * 2. Track B: 自叙轨 (Agent Self-Experiences) - 自主经历与新鲜见闻
 * 3. 念头池: Unspoken Thoughts - 碎片念头、自然衰减与紧急度优先
 * 4. 会话轨: Chat Dialogues - 多轮历史归档
 *
 * 为 PhiMiddleware 动态上下文装配提供完备的高质量认知活水。
 */
class DualTrackMemoryManager(
    private val database: XiaoxiDatabase
) {
    private val userMemoryDao = database.userMemoryDao()
    private val selfExperienceDao = database.selfExperienceDao()
    private val unspokenThoughtDao = database.unspokenThoughtDao()
    private val chatDialogueDao = database.chatDialogueDao()

    /**
     * 用户轨记忆二段分层召回 (Two-Tier Recall)
     *
     * 1. 获取所有 active 状态记忆
     * 2. 计算每个记忆的 6 因子综合得分与 bigram 话题重叠
     * 3. 第一层（话题命中层）：bigramOverlap > 0，按综合得分降序排列
     * 4. 第二层（时序/重要性兜底层）：未命中话题，按综合得分降序排列
     * 5. 话题命中优先浮现，未占满时由第二层补齐至 limit
     */
    suspend fun recallUserMemories(
        query: String = "",
        limit: Int = 10,
        moodValence: Float? = null,
        nowTs: Long = System.currentTimeMillis()
    ): List<UserMemoryEntity> {
        val allActive = userMemoryDao.getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 200)
        if (allActive.isEmpty()) return emptyList()

        data class ScoredMemory(
            val memory: UserMemoryEntity,
            val score: Float,
            val hasTopicMatch: Boolean
        )

        val scoredList = allActive.map { mem ->
            val topicScore = if (query.isNotBlank()) {
                MemoryScorer.bigramOverlap(query, "${mem.content} ${mem.category}")
            } else {
                0.0f
            }
            val totalScore = MemoryScorer.calculateScore(
                memory = mem,
                query = query,
                nowTs = nowTs,
                moodValence = moodValence
            )
            ScoredMemory(
                memory = mem,
                score = totalScore,
                hasTopicMatch = topicScore > 0.0f
            )
        }

        val tier1TopicHits = scoredList
            .filter { it.hasTopicMatch }
            .sortedByDescending { it.score }
            .map { it.memory }

        val tier2Background = scoredList
            .filter { !it.hasTopicMatch }
            .sortedByDescending { it.score }
            .map { it.memory }

        val result = mutableListOf<UserMemoryEntity>()
        result.addAll(tier1TopicHits.take(limit))
        if (result.size < limit) {
            val remaining = limit - result.size
            result.addAll(tier2Background.take(remaining))
        }

        return result
    }

    /**
     * 自叙轨经历召回 (Track B)
     */
    suspend fun recallSelfExperiences(
        limit: Int = 5,
        unsharedOnly: Boolean = false
    ): List<SelfExperienceEntity> {
        return if (unsharedOnly) {
            selfExperienceDao.getUnsharedExperiences(limit = limit)
        } else {
            // 获取新鲜经历，若不足由近期活跃经历（排除 archived，覆盖所有 domain）补充
            val fresh = selfExperienceDao.getUnsharedExperiences(limit = limit)
            if (fresh.size < limit) {
                val needed = limit - fresh.size
                val freshIds = fresh.map { it.id }.toSet()
                val active = selfExperienceDao.getActiveExperiences(limit = limit + fresh.size)
                    .filter { !freshIds.contains(it.id) }
                    .take(needed)
                fresh + active
            } else {
                fresh
            }
        }
    }

    /**
     * 未完成念头池召回与过期自然丢弃
     */
    suspend fun recallUnspokenThoughts(
        limit: Int = 3,
        currentTs: Long = System.currentTimeMillis()
    ): List<UnspokenThoughtEntity> {
        // 先触发自然遗忘：清理已超过 expiryTime 的过期念头
        unspokenThoughtDao.discardExpiredThoughts(currentTs)
        // 提取紧急度最高的 pending 念头
        return unspokenThoughtDao.getTopUrgentPendingThoughts(limit = limit)
    }

    /**
     * 装配双轨记忆动态上下文 (为 PhiMiddleware.assemble 提供标准内容)
     */
    suspend fun assembleDynamicContext(
        query: String = "",
        moodValence: Float? = null,
        charName: String = PhiMiddleware.DEFAULT_CHAR_NAME,
        nowTs: Long = System.currentTimeMillis()
    ): String? {
        val userMemories = recallUserMemories(query, limit = 5, moodValence = moodValence, nowTs = nowTs)
        val selfExperiences = recallSelfExperiences(limit = 2, unsharedOnly = true)
        val unspokenThoughts = recallUnspokenThoughts(limit = 2, currentTs = nowTs)

        if (userMemories.isEmpty() && selfExperiences.isEmpty() && unspokenThoughts.isEmpty()) {
            return null
        }

        return buildString {
            if (userMemories.isNotEmpty()) {
                append("## 关于用户的长期记忆与事实\n")
                for (mem in userMemories) {
                    append("- [${mem.category}] ${mem.content}\n")
                }
                append("\n")
            }

            if (selfExperiences.isNotEmpty()) {
                append("## $charName 最近的个人经历与见闻\n")
                for (exp in selfExperiences) {
                    val insight = if (!exp.emotionalInsight.isNullOrBlank()) "（心得：${exp.emotionalInsight}）" else ""
                    append("- [${exp.domainTag}] ${exp.content}$insight\n")
                }
                append("\n")
            }

            if (unspokenThoughts.isNotEmpty()) {
                append("## $charName 心里惦记的未完成念头\n")
                for (thought in unspokenThoughts) {
                    append("- ${thought.thought}\n")
                }
            }
        }.trim()
    }

    /**
     * 记录记忆命中（按日去重 hit_count 递增）
     */
    suspend fun recordMemoryHit(id: String, todayDate: String, hitTs: Long = System.currentTimeMillis()) {
        userMemoryDao.updateHitCountIfNewDay(id, todayDate, hitTs)
    }

    /**
     * 记录记忆使用与主动召回
     */
    suspend fun markMemoryUsed(id: String, usedTs: Long = System.currentTimeMillis()) {
        userMemoryDao.incrementAccessCount(id, usedTs)
    }

    /**
     * 标记自叙经历已和用户分享
     */
    suspend fun markSelfExperienceShared(id: String, sharedAt: Long = System.currentTimeMillis()) {
        selfExperienceDao.markAsShared(id, sharedAt)
    }

    /**
     * 标记未完成念头已消费
     */
    suspend fun consumeUnspokenThought(id: String, consumedAt: Long = System.currentTimeMillis()) {
        unspokenThoughtDao.markConsumed(id, consumedAt)
    }
}
