package com.lirica.android.automation.verify

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户抢占熔断回归（M5.0 §6.2 · 复审 R4 全量吸收）。
 *
 * 重点覆盖方案没写、但真机上不做就一定出问题的三件事：
 * 自触发抑制、多通道降级、默认不自动恢复。
 */
class UserInterruptionDetectorTest {

    private var now = 10_000L

    private fun detector(autoResumeAfterQuietMs: Long? = null): UserInterruptionDetector =
        UserInterruptionDetector(
            autoResumeAfterQuietMs = autoResumeAfterQuietMs,
            timeProvider = { now }
        )

    @Test
    fun testPhysicalTouchRaisesInterruption() {
        val detector = detector()
        assertTrue(detector.onPhysicalTouch(now))
        assertTrue(detector.isUserActive())
        assertEquals(UserInteractionState.USER_ACTIVE, detector.state.value)
        assertEquals(InterruptionSource.TOUCH_EVENT, detector.source.value)
    }

    @Test
    fun testOwnGestureDoesNotSelfTrigger() {
        // 这是整个机制里最容易写错的一点：dispatchGesture 注入的手势**同样**会产生
        // TYPE_TOUCH_INTERACTION_START。不做抑制窗口，任务会在第 1 步就自我中断。
        val detector = detector()
        detector.onAutomationGestureDispatched(estimatedDurationMs = 200L)
        assertFalse("我方手势产生的触控事件必须被抑制", detector.onPhysicalTouch(now + 100L))
        assertFalse(detector.onPhysicalTouch(now + 400L))
        assertFalse(detector.isUserActive())
    }

    @Test
    fun testTouchAfterSuppressionWindowIsTreatedAsHuman() {
        val detector = detector()
        detector.onAutomationGestureDispatched(estimatedDurationMs = 200L)
        // 抑制窗口 = 200 + 300 = 500ms
        assertTrue(detector.onPhysicalTouch(now + 600L))
        assertTrue(detector.isUserActive())
    }

    @Test
    fun testDefaultPolicyDoesNotAutoResume() {
        // R4 裁决：用户停手 != 用户同意继续。默认必须等显式"继续"。
        val detector = detector(autoResumeAfterQuietMs = null)
        detector.onPhysicalTouch(now)
        now += 60_000L
        assertFalse("未显式继续时不得自动恢复", detector.refresh(now))
        assertTrue(detector.isUserActive())
    }

    @Test
    fun testConfigurableAutoResumeAfterQuietWindow() {
        val detector = detector(autoResumeAfterQuietMs = 5_000L)
        detector.onPhysicalTouch(now)
        now += 5_001L
        assertTrue(detector.refresh(now))
        assertFalse(detector.isUserActive())
        assertEquals(InterruptionSource.NONE, detector.source.value)
    }

    @Test
    fun testExplicitResumeClearsInterruption() {
        val detector = detector()
        detector.onPhysicalTouch(now)
        detector.requestResume()
        assertFalse(detector.isUserActive())
    }

    @Test
    fun testForegroundTakeoverFallbackChannel() {
        // 降级通道 A（R4）：一条触控事件都收不到时，前台应用突变仍能识别用户接管
        val detector = detector()
        assertFalse("首次观测只建立基线，不应误判", detector.onForegroundObserved("com.example.app"))
        assertTrue(detector.onForegroundObserved("com.tencent.mm"))
        assertEquals(InterruptionSource.FOREGROUND_TAKEOVER, detector.source.value)
    }

    @Test
    fun testAutomationForegroundChangeDoesNotTrigger() {
        // 我方主动拉起应用时不得把自己的动作当成用户接管
        val detector = detector()
        detector.onForegroundObserved("com.example.app")
        detector.onAutomationForegroundChange("tv.danmaku.bili")
        assertFalse(detector.onForegroundObserved("tv.danmaku.bili"))
        assertFalse(detector.isUserActive())
    }

    @Test
    fun testForegroundAmnestyWindowSkipsAutomationCausedChange() {
        // M6.0 P1-2 接线：点击"用浏览器打开"这类跨应用跳转是我方动作引发的合法
        // 前台突变——豁免期内只刷新基线，不判接管，否则跨应用任务必被误杀
        val detector = detector()
        detector.onForegroundObserved("com.example.app")
        detector.onAutomationForegroundChangeExpected()
        // 豁免窗口内（now + 2000ms = 窗口边界）：跳到浏览器不判接管
        now += 1_500L
        assertFalse("豁免期内的前台跳变不应判为用户接管", detector.onForegroundObserved("com.android.browser", now))
        assertFalse(detector.isUserActive())
    }

    @Test
    fun testForegroundChangeAfterAmnestyIsStillDetected() {
        // 豁免只覆盖我方动作窗口；豁免结束后再突变仍是强接管信号，
        // 且基线已被豁免期刷新为最新包名（不会被旧基线误判）
        val detector = detector()
        detector.onForegroundObserved("com.example.app")
        detector.onAutomationForegroundChangeExpected()
        now += 1_500L
        assertFalse(detector.onForegroundObserved("com.android.browser", now))
        now += 1_000L // 超出豁免窗口
        assertTrue(detector.onForegroundObserved("com.tencent.mm", now))
        assertEquals(InterruptionSource.FOREGROUND_TAKEOVER, detector.source.value)
    }

    @Test
    fun testGestureAnomalyFallbackChannel() {
        // 降级通道 B（R4）：真实手指在屏会让系统取消注入手势
        val detector = detector()
        assertTrue(detector.onGestureAnomaly(now))
        assertEquals(InterruptionSource.GESTURE_ANOMALY, detector.source.value)
        assertTrue(detector.isUserActive())
    }

    @Test
    fun testQuietDurationTracking() {
        val detector = detector()
        assertEquals(0L, detector.quietDurationMs(now))
        detector.onPhysicalTouch(now)
        now += 1_234L
        assertEquals(1_234L, detector.quietDurationMs(now))
    }

    @Test
    fun testAwaitClearanceReturnsImmediatelyWhenIdle() = runTest {
        assertTrue(detector().awaitClearance(timeoutMs = 100L))
    }

    @Test
    fun testAwaitClearanceTimesOutWhenUserKeepsHolding() = runTest {
        val detector = detector()
        detector.onPhysicalTouch(now)
        assertFalse(detector.awaitClearance(timeoutMs = 50L))
    }

    @Test
    fun testAwaitClearanceResolvesOnExplicitResume() = runTest {
        val detector = detector()
        detector.onPhysicalTouch(now)
        val job = launch {
            kotlinx.coroutines.delay(10L)
            detector.requestResume()
        }
        assertTrue(detector.awaitClearance(timeoutMs = 5_000L))
        job.join()
    }
}
