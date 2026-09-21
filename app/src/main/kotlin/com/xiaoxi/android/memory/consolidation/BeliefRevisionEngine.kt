package com.xiaoxi.android.memory.consolidation

import com.xiaoxi.android.database.entity.UserMemoryEntity

data class RevisionResult(
    val supersededMemories: List<UserMemoryEntity>,
    val activeMemories: List<UserMemoryEntity>
)

/**
 * 矛盾消歧与信念修正引擎 (Belief Revision Engine)
 *
 * 发现新旧认知冲突时，将旧条目更新为 status = 'superseded'，
 * 记录 superseded_by、prev_content 与 valid_to，保留认识论审计轨迹。
 */
class BeliefRevisionEngine {

    companion object {
        // 矛盾对特征映射 (旧特征关键词组 to 新反转排斥关键词组)
        private val CONFLICT_PAIRS = listOf(
            // 咖啡
            setOf("咖啡", "美式", "黑咖啡", "拿铁") to setOf("戒掉咖啡", "不喝咖啡", "戒咖啡", "改喝大麦茶", "大麦茶"),
            // 吃辣
            setOf("吃辣", "麻辣", "无辣不欢", "喜欢辣") to setOf("不能吃辣", "严禁吃辣", "戒辣", "不吃辣", "清淡温热"),
            // 居住地
            setOf("住在北京", "在北京") to setOf("搬到了上海", "住在上海", "去上海生活"),
            // 感情状态
            setOf("单身") to setOf("恋爱中", "结婚了", "有对象")
        )
    }

    fun detectAndRevise(
        existingMemories: List<UserMemoryEntity>,
        newMemories: List<UserMemoryEntity>,
        nowMs: Long
    ): RevisionResult {
        val superseded = mutableListOf<UserMemoryEntity>()
        val active = newMemories.toMutableList()

        for (oldMem in existingMemories) {
            if (oldMem.status != UserMemoryEntity.STATUS_ACTIVE) continue

            var isContradicted = false
            var contradictor: UserMemoryEntity? = null

            for (newMem in newMemories) {
                if (hasContradiction(oldMem.content, newMem.content)) {
                    isContradicted = true
                    contradictor = newMem
                    break
                }
            }

            if (isContradicted && contradictor != null) {
                val updatedOld = oldMem.copy(
                    status = UserMemoryEntity.STATUS_SUPERSEDED,
                    supersededBy = contradictor.id,
                    prevContent = oldMem.content,
                    validTo = nowMs
                )
                superseded.add(updatedOld)
            } else {
                active.add(oldMem)
            }
        }

        return RevisionResult(
            supersededMemories = superseded,
            activeMemories = active
        )
    }

    private fun hasContradiction(oldText: String, newText: String): Boolean {
        for ((oldTerms, newTerms) in CONFLICT_PAIRS) {
            val oldMatches = oldTerms.any { oldText.contains(it) }
            val newMatches = newTerms.any { newText.contains(it) }
            if (oldMatches && newMatches) {
                return true
            }
        }
        return false
    }
}
