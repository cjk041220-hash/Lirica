package com.xiaoxi.android.proactivity

import com.xiaoxi.android.perception.model.ForegroundAppInfo
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.perception.model.ScreenState
import com.xiaoxi.android.perception.model.ScreenStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SilenceDecisionEngineTest {

    private lateinit var estimator: InterruptibilityEstimator
    private lateinit var breakpointDetector: BreakpointDetector
    private lateinit var engine: SilenceDecisionEngine

    @Before
    fun setUp() {
        estimator = InterruptibilityEstimatorImpl()
        breakpointDetector = BreakpointDetectorImpl()
        engine = SilenceDecisionEngineImpl(estimator, breakpointDetector)
    }

    private fun createSnapshot(
        packageName: String = "com.android.launcher",
        screenState: ScreenState = ScreenState.UNLOCKED,
        dwellDurationMs: Long = 10_000L,
        isPrivacyFusing: Boolean = false
    ): PerceptionSnapshot {
        return PerceptionSnapshot(
            timestamp = System.currentTimeMillis(),
            foregroundApp = ForegroundAppInfo(
                packageName = packageName,
                timestamp = System.currentTimeMillis(),
                dwellDurationMs = dwellDurationMs
            ),
            screenState = ScreenStateSnapshot(
                state = screenState,
                lastStateChangeTimestamp = System.currentTimeMillis() - dwellDurationMs,
                totalInteractiveDurationMs = if (screenState == ScreenState.OFF) 0L else dwellDurationMs,
                totalStandbyDurationMs = if (screenState == ScreenState.OFF) dwellDurationMs else 0L
            ),
            latestNotification = null,
            pageContent = null,
            isPrivacyFusing = isPrivacyFusing,
            perceivedSummary = "Context"
        )
    }

    // --- InterruptibilityEstimator Tests ---

    @Test
    fun testEstimator_launcherAppReducesCost() {
        val snapshot = createSnapshot(packageName = "com.android.launcher")
        val now = 100_000L
        val lastInterruption = 0L // > 60 min ago
        val cost = estimator.estimateCost(snapshot, lastInterruption, now)
        // Base 2.0 - 1.0 (launcher discount) = 1.0
        assertEquals(1.0, cost, 0.01)
    }

    @Test
    fun testEstimator_gamingAppIncreasesCostSignificantly() {
        val snapshot = createSnapshot(packageName = "com.tencent.tmgp.sgame")
        val now = 100_000L
        val lastInterruption = 0L
        val cost = estimator.estimateCost(snapshot, lastInterruption, now)
        // Base 2.0 + 2.5 (game) = 4.5
        assertEquals(4.5, cost, 0.01)
    }

    @Test
    fun testEstimator_meetingAppIncreasesCostToMax() {
        val snapshot = createSnapshot(packageName = "us.zoom.videomeetings")
        val now = 100_000L
        val lastInterruption = 0L
        val cost = estimator.estimateCost(snapshot, lastInterruption, now)
        assertEquals(4.5, cost, 0.01)
    }

    @Test
    fun testEstimator_screenOffImposesHighPenalty() {
        val snapshot = createSnapshot(packageName = "com.android.launcher", screenState = ScreenState.OFF)
        val now = 100_000L
        val lastInterruption = 0L
        val cost = estimator.estimateCost(snapshot, lastInterruption, now)
        // Base 2.0 - 1.0 (launcher) + 3.0 (screen off) = 4.0
        assertEquals(4.0, cost, 0.01)
    }

    @Test
    fun testEstimator_cooldownPenaltyDecaysLinearly() {
        val snapshot = createSnapshot(packageName = "com.android.launcher")
        val now = 3600_000L // 1 hour mark
        // Interrupted 30 min ago -> remaining fraction = 0.5 -> penalty = 0.5 * 2.0 = 1.0
        val lastInterruption = now - (30 * 60 * 1000L)
        val cost = estimator.estimateCost(snapshot, lastInterruption, now)
        // Base 2.0 - 1.0 (launcher) + 1.0 (cooldown) = 2.0
        assertEquals(2.0, cost, 0.05)
    }

    @Test
    fun testEstimator_privacySensitiveSetsMaxCost() {
        val snapshot = createSnapshot(packageName = "com.eg.android.AlipayGphone", isPrivacyFusing = true)
        val now = 100_000L
        val cost = estimator.estimateCost(snapshot, 0L, now)
        assertEquals(InterruptibilityEstimator.MAX_COST, cost, 0.01)
    }

    // --- BreakpointDetector Tests ---

    @Test
    fun testBreakpoint_screenUnlock() {
        val prev = createSnapshot(screenState = ScreenState.ON)
        val curr = createSnapshot(screenState = ScreenState.UNLOCKED)
        val result = breakpointDetector.detect(prev, curr)
        assertTrue(result.isBreakpoint)
        assertEquals(BreakpointType.SCREEN_UNLOCK, result.type)
        assertEquals(1.3, result.bonusFactor, 0.01)
    }

    @Test
    fun testBreakpoint_returnToLauncher() {
        val prev = createSnapshot(packageName = "com.tencent.mm")
        val curr = createSnapshot(packageName = "com.android.launcher")
        val result = breakpointDetector.detect(prev, curr)
        assertTrue(result.isBreakpoint)
        assertEquals(BreakpointType.RETURN_TO_LAUNCHER, result.type)
        assertEquals(1.4, result.bonusFactor, 0.01)
    }

    @Test
    fun testBreakpoint_appSwitch() {
        val prev = createSnapshot(packageName = "com.tencent.mm")
        val curr = createSnapshot(packageName = "com.zhihu.android")
        val result = breakpointDetector.detect(prev, curr)
        assertTrue(result.isBreakpoint)
        assertEquals(BreakpointType.APP_SWITCH, result.type)
        assertEquals(1.2, result.bonusFactor, 0.01)
    }

    @Test
    fun testBreakpoint_longIdleInLauncher() {
        val prev = createSnapshot(packageName = "com.android.launcher", dwellDurationMs = 10_000L)
        val curr = createSnapshot(packageName = "com.android.launcher", dwellDurationMs = 35_000L)
        val result = breakpointDetector.detect(prev, curr)
        assertTrue(result.isBreakpoint)
        assertEquals(BreakpointType.LONG_IDLE, result.type)
        assertEquals(1.1, result.bonusFactor, 0.01)
    }

    @Test
    fun testBreakpoint_continuousUsage_noBreakpoint() {
        val prev = createSnapshot(packageName = "com.zhihu.android", dwellDurationMs = 10_000L)
        val curr = createSnapshot(packageName = "com.zhihu.android", dwellDurationMs = 15_000L)
        val result = breakpointDetector.detect(prev, curr)
        assertFalse(result.isBreakpoint)
        assertEquals(BreakpointType.NONE, result.type)
        assertEquals(1.0, result.bonusFactor, 0.01)
    }

    // --- SilenceDecisionEngine Dual-Threshold Gating Tests ---

    @Test
    fun testDecision_lowMotivation_isSilent() {
        val curr = createSnapshot(packageName = "com.android.launcher")
        val now = 10_000_000L
        val result = engine.evaluate(
            curr = curr,
            innerMotivation = 2.5, // < IM_THRESHOLD (3.6)
            lastInterruptionMs = 0L,
            nowMs = now
        )
        assertEquals(ProactiveAction.SILENT, result.action)
    }

    @Test
    fun testDecision_moderateMotivation_highCost_innerThoughtOnly() {
        val curr = createSnapshot(packageName = "com.tencent.tmgp.sgame") // gaming, cost ~4.5
        val now = 10_000_000L
        val result = engine.evaluate(
            curr = curr,
            innerMotivation = 4.0, // >= IM_THRESHOLD (3.6) but suppressed by high cost (< INTERRUPT_THRESHOLD)
            lastInterruptionMs = 0L,
            nowMs = now
        )
        assertEquals(ProactiveAction.INNER_THOUGHT_ONLY, result.action)
    }

    @Test
    fun testDecision_highMotivation_lowCost_triggersProactiveInterrupt() {
        val curr = createSnapshot(packageName = "com.android.launcher") // launcher, cost = 1.0
        val now = 10_000_000L
        val result = engine.evaluate(
            curr = curr,
            innerMotivation = 4.6, // >= INTERRUPT_THRESHOLD (4.5)
            lastInterruptionMs = 0L,
            nowMs = now
        )
        assertEquals(ProactiveAction.PROACTIVE_INTERRUPT, result.action)
    }

    @Test
    fun testDecision_breakpointBoostPromotesToProactiveInterrupt() {
        val prev = createSnapshot(packageName = "com.tencent.mm")
        val curr = createSnapshot(packageName = "com.android.launcher") // Return to launcher, bonusFactor = 1.4
        val now = 10_000_000L
        // Motivation 3.8 alone wouldn't reach 4.5, but with 1.4 bonus (3.8 * 1.4 = 5.32) - (1.0 - 2.0) = 6.32 >= 4.5
        val result = engine.evaluate(
            curr = curr,
            innerMotivation = 3.8,
            lastInterruptionMs = 0L,
            nowMs = now,
            prev = prev
        )
        assertEquals(ProactiveAction.PROACTIVE_INTERRUPT, result.action)
    }

    @Test
    fun testDecision_privacySensitiveHardBlocksInterrupt() {
        val curr = createSnapshot(packageName = "com.eg.android.AlipayGphone", isPrivacyFusing = true)
        val now = 10_000_000L
        val result = engine.evaluate(
            curr = curr,
            innerMotivation = 5.0, // Extremely high motivation
            lastInterruptionMs = 0L,
            nowMs = now
        )
        // Privacy fusing strictly blocks interruption to INNER_THOUGHT_ONLY
        assertEquals(ProactiveAction.INNER_THOUGHT_ONLY, result.action)
    }

    @Test
    fun testDecision_screenOffBlocksInterrupt() {
        val curr = createSnapshot(screenState = ScreenState.OFF)
        val now = 10_000_000L
        val result = engine.evaluate(
            curr = curr,
            innerMotivation = 4.8,
            lastInterruptionMs = 0L,
            nowMs = now
        )
        assertEquals(ProactiveAction.INNER_THOUGHT_ONLY, result.action)
    }
}
