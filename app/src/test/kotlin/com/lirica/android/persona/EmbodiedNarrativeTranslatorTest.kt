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
    fun testTranslate_longOffline_generatesReunionNarrative() {
        val hw = HardwareEnvironmentState()
        val screen = ScreenStateSnapshot(state = ScreenState.UNLOCKED)
        val result = EmbodiedNarrativeTranslator.translate(
            hw = hw,
            screen = screen,
            hourOfDay = 10,
            dwellDurationMs = 6 * 3600 * 1000L
        )

        assertTrue(result.contains("重新解锁"))
    }
}