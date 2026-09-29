package com.lirica.android.automation.healing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelfHealingWatchdogTest {

    private lateinit var watchdog: SelfHealingWatchdogImpl

    @Before
    fun setUp() {
        watchdog = SelfHealingWatchdogImpl(
            maxStuckCount = 3,
            maxRecoveryAttempts = 3
        )
    }

    @Test
    fun testInspectStep_normalProgressEvolvingFingerprints() {
        val v1 = watchdog.inspectStep(1001L, "com.target.app", "com.target.app", "Click button A")
        assertTrue(v1 is WatchdogVerdict.NormalProgress)
        assertEquals(1, (v1 as WatchdogVerdict.NormalProgress).stepIndex)

        val v2 = watchdog.inspectStep(1002L, "com.target.app", "com.target.app", "Click button B")
        assertTrue(v2 is WatchdogVerdict.NormalProgress)
        assertEquals(2, (v2 as WatchdogVerdict.NormalProgress).stepIndex)
    }

    @Test
    fun testInspectStep_stuckLoop_triggersReGroundFirstThenBacktrack() {
        // Step 1: initial state 2000L
        watchdog.inspectStep(2000L, "com.target.app", "com.target.app", "Click Item")
        // Step 2: same state 2000L (consecutive = 2)
        val v2 = watchdog.inspectStep(2000L, "com.target.app", "com.target.app", "Click Item")
        assertTrue(v2 is WatchdogVerdict.NormalProgress)

        // Step 3: same state 2000L (consecutive = 3 -> STUCK_LOOP)
        val v3 = watchdog.inspectStep(2000L, "com.target.app", "com.target.app", "Click Item")
        assertTrue(v3 is WatchdogVerdict.Recoverable)
        val recoverable1 = v3 as WatchdogVerdict.Recoverable
        assertEquals(AnomalyType.STUCK_LOOP, recoverable1.anomaly)
        assertTrue(recoverable1.suggestedAction is HealingAction.ReGround)
        assertEquals(1, recoverable1.attemptCount)

        // Record attempt and fail again
        watchdog.recordRecoveryAttempt(AnomalyType.STUCK_LOOP)
        val v4 = watchdog.inspectStep(2000L, "com.target.app", "com.target.app", "Click Item")
        assertTrue(v4 is WatchdogVerdict.Recoverable)
        val recoverable2 = v4 as WatchdogVerdict.Recoverable
        assertTrue(recoverable2.suggestedAction is HealingAction.PhysicalBacktrack)
        assertEquals(2, recoverable2.attemptCount)
    }

    @Test
    fun testInspectStep_stuckLoop_exceedsMaxAttempts_triggersUnrecoverable() {
        watchdog.inspectStep(3000L, "com.target.app", "com.target.app", "Click")
        watchdog.inspectStep(3000L, "com.target.app", "com.target.app", "Click")

        watchdog.recordRecoveryAttempt(AnomalyType.STUCK_LOOP) // 1
        watchdog.recordRecoveryAttempt(AnomalyType.STUCK_LOOP) // 2
        watchdog.recordRecoveryAttempt(AnomalyType.STUCK_LOOP) // 3

        val verdict = watchdog.inspectStep(3000L, "com.target.app", "com.target.app", "Click")
        assertTrue(verdict is WatchdogVerdict.Unrecoverable)
        val unrec = verdict as WatchdogVerdict.Unrecoverable
        assertTrue(unrec.reason.contains("Exceeded max recovery attempts"))
        // 批次 3: 验证 Unrecoverable 携带由 HonestNarrator 渲染的坦白文案
        assertNotNull(unrec.honestNarrative)
        assertTrue("坦白文案必须非空", unrec.honestNarrative!!.isNotBlank())
        assertTrue("坦白文案必须包含待定稿标语", unrec.honestNarrative!!.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testInspectStep_unrecoverable_injectsCustomHonestNarrator() {
        var narratedContext: FailureContext? = null
        val customNarrator = object : HonestNarrator {
            override fun narrate(context: FailureContext): String {
                narratedContext = context
                return "自定义坦白文案：我真的尽力了"
            }
            override fun getTemplates(scenario: FailureScenario): List<String> = emptyList()
        }

        val customWatchdog = SelfHealingWatchdogImpl(
            maxStuckCount = 1,
            maxRecoveryAttempts = 1,
            honestNarrator = customNarrator
        )

        customWatchdog.recordRecoveryAttempt(AnomalyType.STUCK_LOOP)
        val verdict = customWatchdog.inspectStep(9999L, "com.custom.app", "com.custom.app", "Click")

        assertTrue(verdict is WatchdogVerdict.Unrecoverable)
        val unrec = verdict as WatchdogVerdict.Unrecoverable
        assertEquals("自定义坦白文案：我真的尽力了", unrec.honestNarrative)
        assertNotNull(narratedContext)
        assertEquals(FailureScenario.SELF_HEALING_EXHAUSTED, narratedContext?.scenario)
        assertEquals("com.custom.app", narratedContext?.targetPackage)
        assertTrue(narratedContext?.technicalReason?.contains("Exceeded max recovery attempts") == true)
    }

    @Test
    fun testInspectStep_unexpectedExit_exceedsMaxAttempts_triggersUnrecoverableWithNarrative() {
        val customWatchdog = SelfHealingWatchdogImpl(
            maxRecoveryAttempts = 2
        )
        customWatchdog.recordRecoveryAttempt(AnomalyType.UNEXPECTED_EXIT)
        customWatchdog.recordRecoveryAttempt(AnomalyType.UNEXPECTED_EXIT)

        val verdict = customWatchdog.inspectStep(
            fingerprint = 5002L,
            currentPackage = "com.miui.home",
            targetPackage = "com.tencent.mm",
            actionSummary = "Click Chat"
        )

        assertTrue(verdict is WatchdogVerdict.Unrecoverable)
        val unrec = verdict as WatchdogVerdict.Unrecoverable
        assertTrue(unrec.reason.contains("UNEXPECTED_EXIT"))
        assertNotNull(unrec.honestNarrative)
        assertTrue(unrec.honestNarrative!!.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testInspectStep_popupBlocking_triggersDismissPopup() {
        // Step 1: Normal
        watchdog.inspectStep(4001L, "com.target.app", "com.target.app", "Open home")

        // Step 2: Interstitial pop-up with "跳过" (Skip)
        val pageTexts = listOf("限时优惠", "天天领红包", "跳过")
        val v2 = watchdog.inspectStep(4002L, "com.target.app", "com.target.app", "Waiting", pageTexts)

        assertTrue(v2 is WatchdogVerdict.Recoverable)
        val recoverable = v2 as WatchdogVerdict.Recoverable
        assertEquals(AnomalyType.POPUP_BLOCKING, recoverable.anomaly)
        assertTrue(recoverable.suggestedAction is HealingAction.DismissPopup)
        assertEquals("跳过", (recoverable.suggestedAction as HealingAction.DismissPopup).targetKeyword)
    }

    @Test
    fun testInspectStep_unexpectedExit_triggersPhysicalBacktrack() {
        // App unexpectedly drops to Xiaomi Launcher
        val verdict = watchdog.inspectStep(
            fingerprint = 5001L,
            currentPackage = "com.miui.home",
            targetPackage = "com.tencent.mm",
            actionSummary = "Click Chat"
        )

        assertTrue(verdict is WatchdogVerdict.Recoverable)
        val recoverable = verdict as WatchdogVerdict.Recoverable
        assertEquals(AnomalyType.UNEXPECTED_EXIT, recoverable.anomaly)
        assertTrue(recoverable.suggestedAction is HealingAction.PhysicalBacktrack)
    }

    @Test
    fun testReset_clearsHistoryAndRecoveryAttempts() {
        watchdog.recordRecoveryAttempt(AnomalyType.STUCK_LOOP)
        watchdog.inspectStep(6001L, "com.target.app", "com.target.app", "Click")
        watchdog.inspectStep(6001L, "com.target.app", "com.target.app", "Click")

        watchdog.reset()

        // After reset, state should be fresh (consecutive = 1)
        val fresh = watchdog.inspectStep(6001L, "com.target.app", "com.target.app", "Click")
        assertTrue(fresh is WatchdogVerdict.NormalProgress)
        assertEquals(1, (fresh as WatchdogVerdict.NormalProgress).stepIndex)
    }
}
