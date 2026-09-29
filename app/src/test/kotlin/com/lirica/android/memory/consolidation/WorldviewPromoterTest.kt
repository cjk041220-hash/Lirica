package com.lirica.android.memory.consolidation

import com.lirica.android.database.entity.UserMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldviewPromoterTest {

    private val promoter = WorldviewPromoter()

    @Test
    fun testEvaluatePromotions_promotesRecurringHabitsAndPreferences_filtersSingleOccurrences() {
        val recurringMemory1 = UserMemoryEntity(
            id = "m1",
            content = "用户习惯深度工作时关闭所有社交软件免打扰",
            category = "工作习惯",
            importance = 8,
            confidence = 0.9f,
            hitCount = 4,
            accessCount = 5,
            createdAt = 1000L
        )
        val recurringMemory2 = UserMemoryEntity(
            id = "m2",
            content = "用户在生活上追求极简主义，讨厌无意义的繁琐流程",
            category = "生活哲学",
            importance = 9,
            confidence = 0.95f,
            hitCount = 3,
            accessCount = 4,
            createdAt = 2000L
        )
        val singleOccasionMemory = UserMemoryEntity(
            id = "m3",
            content = "用户今天去超市买了一袋苹果",
            category = "日常生活",
            importance = 4,
            confidence = 0.8f,
            hitCount = 1,
            accessCount = 0,
            createdAt = 3000L
        )

        val input = listOf(recurringMemory1, recurringMemory2, singleOccasionMemory)
        val worldviews = promoter.evaluatePromotions(input, nowMs = 5000L)

        // 单次事件 m3 绝不晋升
        assertEquals(2, worldviews.size)

        val wv1 = worldviews.first { it.category == "工作习惯" }
        assertEquals("用户习惯深度工作时关闭所有社交软件免打扰", wv1.statement)
        assertEquals(4, wv1.supportingEvidenceCount)
        assertEquals(5000L, wv1.lastConfirmedAt)

        val wv2 = worldviews.first { it.category == "生活哲学" }
        assertEquals("用户在生活上追求极简主义，讨厌无意义的繁琐流程", wv2.statement)
        assertEquals(3, wv2.supportingEvidenceCount)
    }
}
