package com.xiaoxi.android.memory.consolidation

import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.memory.MemoryScorer

data class DeduplicationResult(
    val survivingMemories: List<UserMemoryEntity>,
    val archivedMemories: List<UserMemoryEntity>
) {
    val reductionRate: Float
        get() = if (survivingMemories.size + archivedMemories.size > 0) {
            archivedMemories.size.toFloat() / (survivingMemories.size + archivedMemories.size)
        } else 0f
}

/**
 * 语义记忆去重合并器 (Semantic Deduplicator)
 * 严格执行反囤积铁律（Anti-Hoarding）：消化归纳后的记忆总量必须 <= 原始总量
 */
class SemanticDeduplicator {

    companion object {
        private val STOP_CHARS = setOf('用', '户', '的', '了', '在', '是', '有', '和', '与', '也', '很', '常')
    }

    fun deduplicate(memories: List<UserMemoryEntity>): DeduplicationResult {
        if (memories.isEmpty()) {
            return DeduplicationResult(emptyList(), emptyList())
        }

        val surviving = mutableListOf<UserMemoryEntity>()
        val archived = mutableListOf<UserMemoryEntity>()

        // 按分类分组聚类去重
        val grouped = memories.groupBy { it.category }

        for ((_, group) in grouped) {
            val clusters = mutableListOf<MutableList<UserMemoryEntity>>()

            for (mem in group) {
                var placed = false
                for (cluster in clusters) {
                    val isSimilarToAny = cluster.any { isSemanticallyRelated(mem.content, it.content) }
                    if (isSimilarToAny) {
                        cluster.add(mem)
                        placed = true
                        break
                    }
                }
                if (!placed) {
                    clusters.add(mutableListOf(mem))
                }
            }

            for (cluster in clusters) {
                if (cluster.size == 1) {
                    surviving.add(cluster.first())
                } else {
                    // 选出信息量最丰富、重要性最高的条目作为代表
                    val primary = cluster.maxWithOrNull(
                        compareBy<UserMemoryEntity> { it.importance }
                            .thenBy { it.content.length }
                            .thenBy { it.confidence }
                    ) ?: cluster.first()

                    val totalAccess = cluster.sumOf { it.accessCount }
                    val totalHit = cluster.sumOf { it.hitCount }
                    val maxLastMention = cluster.maxOf { it.lastMentionTs }
                    val maxImportance = cluster.maxOf { it.importance }
                    val maxConfidence = cluster.map { it.confidence }.maxOrNull() ?: primary.confidence

                    val merged = primary.copy(
                        accessCount = totalAccess,
                        hitCount = totalHit,
                        lastMentionTs = maxLastMention,
                        importance = maxImportance,
                        confidence = maxConfidence
                    )
                    surviving.add(merged)

                    // 其它碎片条目归档
                    for (other in cluster) {
                        if (other.id != primary.id) {
                            archived.add(
                                other.copy(
                                    status = UserMemoryEntity.STATUS_ARCHIVED,
                                    supersededBy = primary.id
                                )
                            )
                        }
                    }
                }
            }
        }

        return DeduplicationResult(surviving, archived)
    }

    private fun isSemanticallyRelated(text1: String, text2: String): Boolean {
        // 1. 子串包含
        if (text1.contains(text2) || text2.contains(text1)) return true

        // 2. Bigram 重叠度
        val overlap = MemoryScorer.bigramOverlap(text1, text2)
        if (overlap >= 0.35f) return true

        // 3. 核心实体字符重叠 (过滤通用虚词)
        val s1 = text1.filter { it !in STOP_CHARS }.toSet()
        val s2 = text2.filter { it !in STOP_CHARS }.toSet()
        val common = s1.intersect(s2)
        val minLen = minOf(s1.size, s2.size)

        if (minLen >= 2 && common.size >= 2) {
            val ratio = common.size.toFloat() / minLen
            if (ratio >= 0.40f) return true
        }

        return false
    }
}
