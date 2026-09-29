package com.lirica.android.proactivity

import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.life.CareHint
import com.lirica.android.life.CareHintDetector
import com.lirica.android.life.CareHintType
import com.lirica.android.perception.model.AccessibilityNodeSnapshot
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.PageContentSnapshot
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtSynthesizerPerceptionTest {

    @Test
    fun testJobSearch_synthesizesThought() {
        val synthesizer = ThoughtSynthesizerImpl()

        val pageSnapshot = PageContentSnapshot(
            packageName = "com.hpbr.bosszhipin",
            nodes = listOf(
                AccessibilityNodeSnapshot(text = "Android 资深架构师"),
                AccessibilityNodeSnapshot(text = "投递简历"),
                AccessibilityNodeSnapshot(text = "期望薪资 35k-50k")
            )
        )

        val snapshot = PerceptionSnapshot(
            timestamp = 1000L,
            foregroundApp = ForegroundAppInfo("com.hpbr.bosszhipin", "MainActivity", 1000L, 5000L),
            screenState = ScreenStateSnapshot(ScreenState.UNLOCKED),
            pageContent = pageSnapshot
        )

        val thought = synthesizer.synthesize(snapshot, EmotionVector(0f, 0f), 3.0)
        assertNotNull(thought)
        assertTrue(thought!!.thought.contains("找工作") || thought.thought.contains("招聘"))

        // 同一冷却期内重复扫描返回 null
        val thought2 = synthesizer.synthesize(snapshot, EmotionVector(0f, 0f), 3.0)
        assertNull(thought2)
    }

    @Test
    fun testRentHouse_synthesizesThought() {
        val synthesizer = ThoughtSynthesizerImpl()

        val pageSnapshot = PageContentSnapshot(
            packageName = "com.homelink.lianjia",
            nodes = listOf(
                AccessibilityNodeSnapshot(text = "朝阳区两居室整租"),
                AccessibilityNodeSnapshot(text = "优质精装房源，押一付一")
            )
        )

        val snapshot = PerceptionSnapshot(
            timestamp = 1000L,
            foregroundApp = ForegroundAppInfo("com.homelink.lianjia", "MainActivity", 1000L, 5000L),
            screenState = ScreenStateSnapshot(ScreenState.UNLOCKED),
            pageContent = pageSnapshot
        )

        val thought = synthesizer.synthesize(snapshot, EmotionVector(0f, 0f), 3.0)
        assertNotNull(thought)
        assertTrue(thought!!.thought.contains("房源") || thought.thought.contains("小窝"))
    }

    @Test
    fun testLateNightCare_synthesizesHighUrgencyThought() {
        val careDetector: CareHintDetector = mockk()
        every {
            careDetector.collectHintTexts(any(), any(), any(), any())
        } returns listOf("深夜还在醒着，已经连续熬夜了")

        val synthesizer = ThoughtSynthesizerImpl(careHintDetector = careDetector)

        val snapshot = PerceptionSnapshot(
            timestamp = 1000L,
            foregroundApp = ForegroundAppInfo("com.example.app", "MainActivity", 1000L, 5000L),
            screenState = ScreenStateSnapshot(ScreenState.UNLOCKED)
        )

        val thought = synthesizer.synthesize(snapshot, EmotionVector(0f, 0f), 2.0)
        assertNotNull(thought)
        assertTrue(thought!!.thought.contains("夜深了") || thought.thought.contains("早点休息"))
        assertEquals(0.92f, thought.urgency, 0.01f)
    }
}
