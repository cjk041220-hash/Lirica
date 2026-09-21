package com.xiaoxi.android.memory.consolidation

import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.entity.WorldviewEntity

/**
 * 高层世界观晋升引擎 (Worldview Promoter)
 *
 * 只有在不同日期、多个独立情境下反复得到验证的规律（hitCount >= 3 或重要性极高），
 * 才允许被提炼并晋升至高层世界观表。
 */
class WorldviewPromoter(
    private val minHitCountThreshold: Int = 3
) {

    fun evaluatePromotions(
        memories: List<UserMemoryEntity>,
        nowMs: Long
    ): List<WorldviewEntity> {
        val candidates = memories.filter {
            it.status == UserMemoryEntity.STATUS_ACTIVE &&
                (it.hitCount >= minHitCountThreshold || it.importance >= 8)
        }

        return candidates.map { mem ->
            WorldviewEntity(
                id = "wv_${mem.id}",
                category = mem.category,
                statement = mem.content,
                supportingEvidenceCount = mem.hitCount.coerceAtLeast(1),
                lastConfirmedAt = nowMs,
                confidence = mem.confidence
            )
        }
    }
}
