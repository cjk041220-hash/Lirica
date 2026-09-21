package com.xiaoxi.android.memory.consolidation

import com.xiaoxi.android.database.entity.UserMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDeduplicatorTest {

    private val deduplicator = SemanticDeduplicator()

    @Test
    fun testDeduplicate_mergesDuplicateAndOverlappingMemories_reducesTotalCount() {
        val m1 = UserMemoryEntity(
            id = "m1",
            content = "用户买了新耳机",
            category = "偏好",
            importance = 5,
            confidence = 0.8f,
            createdAt = 1000L,
            accessCount = 2,
            hitCount = 1
        )
        val m2 = UserMemoryEntity(
            id = "m2",
            content = "用户喜欢索尼降噪耳机",
            category = "偏好",
            importance = 6,
            confidence = 0.85f,
            createdAt = 2000L,
            accessCount = 3,
            hitCount = 2
        )
        val m3 = UserMemoryEntity(
            id = "m3",
            content = "用户拥有索尼降噪耳机并注重音质体验",
            category = "偏好",
            importance = 8,
            confidence = 0.95f,
            createdAt = 3000L,
            accessCount = 1,
            hitCount = 1
        )
        val m4 = UserMemoryEntity(
            id = "m4",
            content = "用户喜欢在早晨喝黑咖啡",
            category = "作息",
            importance = 7,
            confidence = 0.9f,
            createdAt = 4000L,
            accessCount = 5,
            hitCount = 3
        )

        val input = listOf(m1, m2, m3, m4)
        val result = deduplicator.deduplicate(input)

        // 反囤积铁律：输出的活跃事实数必须严格少于或等于输入数
        assertTrue(result.survivingMemories.size <= input.size)
        // m1, m2, m3 属于同一耳机的语义重叠，应合并为一个核心条目
        assertEquals(2, result.survivingMemories.size)
        assertEquals(2, result.archivedMemories.size)

        // 验证合并后的耳机条目继承了最高的 importance、confidence 以及累加的热度
        val mergedHeadphone = result.survivingMemories.first { it.category == "偏好" }
        assertEquals("m3", mergedHeadphone.id)
        assertEquals("用户拥有索尼降噪耳机并注重音质体验", mergedHeadphone.content)
        assertEquals(8, mergedHeadphone.importance)
        assertEquals(6, mergedHeadphone.accessCount) // 2 + 3 + 1
        assertEquals(4, mergedHeadphone.hitCount) // 1 + 2 + 1
        assertEquals(3000L, mergedHeadphone.lastMentionTs)

        // 验证被合并的 m1 与 m2 被归档
        val archivedIds = result.archivedMemories.map { it.id }.toSet()
        assertTrue(archivedIds.contains("m1"))
        assertTrue(archivedIds.contains("m2"))
        assertTrue(result.archivedMemories.all { it.status == UserMemoryEntity.STATUS_ARCHIVED })
    }

    @Test
    fun testDeduplicate_noOverlap_preservesAll() {
        val m1 = UserMemoryEntity(
            id = "m1",
            content = "用户是Android工程师",
            category = "身份",
            createdAt = 1000L
        )
        val m2 = UserMemoryEntity(
            id = "m2",
            content = "用户养了一只柯基犬",
            category = "生活",
            createdAt = 2000L
        )

        val result = deduplicator.deduplicate(listOf(m1, m2))
        assertEquals(2, result.survivingMemories.size)
        assertEquals(0, result.archivedMemories.size)
    }
}
