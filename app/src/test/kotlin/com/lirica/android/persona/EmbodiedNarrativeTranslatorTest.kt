package com.lirica.android.persona

import com.lirica.android.perception.model.HardwareEnvironmentState
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class EmbodiedNarrativeTranslatorTest {

    @Test
    fun testTranslate_headsetAndAudio_generatesListeningNarrative() {
        val hw = HardwareEnvironmentState(
            isHeadsetPlugged = true,
            isAudioPlaying = true
        )
        val screen = ScreenStateSnapshot(state = ScreenState.UNLOCKED)
        val result = EmbodiedNarrativeTranslator.translate(hw, screen, hourOfDay = 14)

        assertTrue(result.contains("戴着耳机听音乐"))
        assertTrue(result.startsWith("【具身情境："))
    }

    @Test
    fun testTranslate_lateNightUnlocked_generatesLateNightNarrative() {
        val hw = HardwareEnvironmentState(isHeadsetPlugged = false, isAudioPlaying = false)
        val screen = ScreenStateSnapshot(state = ScreenState.UNLOCKED)
        val result = EmbodiedNarrativeTranslator.translate(hw, screen, hourOfDay = 2)

        assertTrue(result.contains("深夜 2 点"))
        assertTrue(result.contains("早点休息"))
    }

    @Test
    fun testTranslate_emptyWhenNoSpecialContext() {
        val hw = HardwareEnvironmentState(isHeadsetPlugged = false, isAudioPlaying = false)
        val screen = ScreenStateSnapshot(state = ScreenState.OFF)
        val result = EmbodiedNarrativeTranslator.translate(hw, screen, hourOfDay = 15)

        assertTrue(result.isBlank())
    }

    @Test
    fun testTranslate_longAbsenceThenUnlock_generatesReunionNarrative() {
        val hw = HardwareEnvironmentState()
        val screen = ScreenStateSnapshot(state = ScreenState.UNLOCKED)
        val result = EmbodiedNarrativeTranslator.translate(
            hw = hw,
            screen = screen,
            hourOfDay = 10,
            absenceDurationMs = 6 * 3600 * 1000L // 息屏/锁屏累计 6 小时
        )

        assertTrue(result.contains("重新解锁"))
    }

    /**
     * 反向用例（本轮修复的回归守卫）：
     * 连续亮屏/使用 6 小时**不是**离开，绝不能被说成"手机沉睡了好久"。
     * 旧实现用屏幕状态变更间隔判定，恰好会把这种场景误判成久别。
     */
    @Test
    fun testTranslate_longContinuousUsage_doesNotClaimPhoneWasSleeping() {
        val hw = HardwareEnvironmentState()
        val screen = ScreenStateSnapshot(
            state = ScreenState.UNLOCKED,
            lastStateChangeTimestamp = 0L,
            lastUserPresentTimestamp = 0L
        )
        val result = EmbodiedNarrativeTranslator.translate(
            hw = hw,
            screen = screen,
            hourOfDay = 15,
            dwellDurationMs = 6 * 3600 * 1000L, // 一直在用
            absenceDurationMs = 0L
        )

        assertFalse(result.contains("沉睡"))
        assertFalse(result.contains("重新解锁"))
    }

    @Test
    fun testTranslate_absenceBelowThreshold_doesNotGenerateReunion() {
        val result = EmbodiedNarrativeTranslator.translate(
            hw = HardwareEnvironmentState(),
            screen = ScreenStateSnapshot(state = ScreenState.UNLOCKED),
            hourOfDay = 14,
            absenceDurationMs = 2 * 3600 * 1000L // 只离开 2 小时
        )

        assertFalse(result.contains("重新解锁"))
    }
}