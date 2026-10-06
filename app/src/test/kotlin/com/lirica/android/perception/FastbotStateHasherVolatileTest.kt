package com.lirica.android.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 易变文本归一化回归（M5.0 §5.1.4 双指纹收敛）。
 */
class FastbotStateHasherVolatileTest {

    @Test
    fun testClockIsNormalized() {
        assertEquals(FastbotStateHasher.VOLATILE_TIME, FastbotStateHasher.normalizeVolatileText("12:34"))
        assertEquals(FastbotStateHasher.VOLATILE_TIME, FastbotStateHasher.normalizeVolatileText("09:05:31"))
    }

    @Test
    fun testDateIsNormalized() {
        assertEquals(FastbotStateHasher.VOLATILE_DATE, FastbotStateHasher.normalizeVolatileText("2026-10-06"))
        assertEquals(FastbotStateHasher.VOLATILE_DATE, FastbotStateHasher.normalizeVolatileText("2026年10月6日"))
    }

    @Test
    fun testPercentIsNormalized() {
        assertEquals(FastbotStateHasher.VOLATILE_PERCENT, FastbotStateHasher.normalizeVolatileText("85%"))
        assertEquals(FastbotStateHasher.VOLATILE_PERCENT, FastbotStateHasher.normalizeVolatileText("7 %"))
    }

    @Test
    fun testUnitCountIsNormalized() {
        assertEquals(FastbotStateHasher.VOLATILE_COUNT, FastbotStateHasher.normalizeVolatileText("1.2万"))
        assertEquals(FastbotStateHasher.VOLATILE_COUNT, FastbotStateHasher.normalizeVolatileText("3.4k"))
    }

    @Test
    fun testStandaloneNumberIsNormalized() {
        assertEquals(FastbotStateHasher.VOLATILE_NUMBER, FastbotStateHasher.normalizeVolatileText("1234"))
    }

    @Test
    fun testLoadingIsNormalized() {
        assertEquals(FastbotStateHasher.VOLATILE_LOADING, FastbotStateHasher.normalizeVolatileText("加载中…"))
    }

    @Test
    fun testBlankAndNullBecomeNull() {
        assertNull(FastbotStateHasher.normalizeVolatileText(null))
        assertNull(FastbotStateHasher.normalizeVolatileText("   "))
    }

    @Test
    fun testMeaningfulTextIsPreserved() {
        // 归一化不能把有语义的文本也抹掉，否则指纹会失去区分能力
        assertEquals("搜索", FastbotStateHasher.normalizeVolatileText(" 搜索 "))
        assertEquals("第 3 章 阅读", FastbotStateHasher.normalizeVolatileText("第 3 章   阅读"))
    }

    @Test
    fun testFingerprintStableAcrossVolatileChanges() {
        val before = listOf(
            ViewSignature(text = "首页", viewId = "id/home", isClickable = true),
            ViewSignature(text = "12:30", viewId = "id/clock"),
            ViewSignature(text = "86%", viewId = "id/battery")
        )
        val after = listOf(
            ViewSignature(text = "首页", viewId = "id/home", isClickable = true),
            ViewSignature(text = "12:31", viewId = "id/clock"),
            ViewSignature(text = "85%", viewId = "id/battery")
        )
        assertEquals(
            FastbotStateHasher.computeViewFingerprint("MainActivity", "com.example.app", before),
            FastbotStateHasher.computeViewFingerprint("MainActivity", "com.example.app", after)
        )
    }

    @Test
    fun testFingerprintChangesOnStructuralMutation() {
        val before = listOf(ViewSignature(text = "首页", isClickable = true))
        val after = listOf(ViewSignature(text = "首页", isClickable = false))
        assertNotEquals(
            FastbotStateHasher.computeViewFingerprint("MainActivity", "com.example.app", before),
            FastbotStateHasher.computeViewFingerprint("MainActivity", "com.example.app", after)
        )
    }

    @Test
    fun testFingerprintChangesAcrossPackages() {
        val nodes = listOf(ViewSignature(text = "首页"))
        assertNotEquals(
            FastbotStateHasher.computeViewFingerprint("MainActivity", "com.a", nodes),
            FastbotStateHasher.computeViewFingerprint("MainActivity", "com.b", nodes)
        )
    }
}
