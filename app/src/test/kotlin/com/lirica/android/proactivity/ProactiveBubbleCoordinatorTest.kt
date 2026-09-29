package com.lirica.android.proactivity

import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.NotificationCategory
import com.lirica.android.perception.model.NotificationEvent
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProactiveBubbleCoordinatorTest {

    private lateinit var emotionEngine: EmotionEngine
    private lateinit var thoughtManager: UnspokenThoughtManager
    private lateinit var decisionEngine: SilenceDecisionEngine
    private lateinit var coordinator: ProactiveBubbleCoordinator

    @Before
    fun setUp() {
        emotionEngine = mockk(relaxed = true)
        thoughtManager = mockk(relaxed = true)
        decisionEngine = mockk(relaxed = true)

        every { emotionEngine.currentVector } returns EmotionVector(0f, 0f)

        coordinator = ProactiveBubbleCoordinatorImpl(
            thoughtManager = thoughtManager,
            decisionEngine = decisionEngine,
            emotionEngine = emotionEngine
        )
    }

    private fun createSnapshot(
        packageName: String = "com.android.launcher",
        screenState: ScreenState = ScreenState.UNLOCKED,
        notification: NotificationEvent? = null
    ): PerceptionSnapshot {
        return PerceptionSnapshot(
            timestamp = 100_000L,
            foregroundApp = ForegroundAppInfo(
                packageName = packageName,
                timestamp = 100_000L,
                dwellDurationMs = 10_000L
            ),
            screenState = ScreenStateSnapshot(
                state = screenState,
                lastStateChangeTimestamp = 90_000L,
                totalInteractiveDurationMs = 10_000L
            ),
            latestNotification = notification,
            pageContent = null,
            isPrivacyFusing = false,
            perceivedSummary = "Normal"
        )
    }

    @Test
    fun testCoordinate_silentWhenMotivationLow_producesL0() = runTest {
        val snapshot = createSnapshot()
        coEvery { thoughtManager.processPerception(snapshot, any(), any()) } returns 2.0 // Low motivation
        every { decisionEngine.evaluate(snapshot, any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.SILENT,
            innerMotivation = 2.0,
            interruptCost = 1.0,
            effectiveMotivation = 2.0,
            breakpoint = BreakpointResult(false, BreakpointType.NONE),
            reason = "Motivation too low"
        )

        val result = coordinator.coordinate(snapshot, nowMs = 100_000L)
        assertEquals(ProactivityLevel.L0_SILENT, result.level)
        assertEquals(0, result.bubblesShownToday)
    }

    @Test
    fun testCoordinate_innerThoughtOnly_producesL1AndStimulatesEmotion() = runTest {
        val snapshot = createSnapshot()
        coEvery { thoughtManager.processPerception(snapshot, any(), any()) } returns 3.8
        every { decisionEngine.evaluate(snapshot, any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.INNER_THOUGHT_ONLY,
            innerMotivation = 3.8,
            interruptCost = 3.0,
            effectiveMotivation = 3.8,
            breakpoint = BreakpointResult(false, BreakpointType.NONE),
            reason = "High cost"
        )

        val result = coordinator.coordinate(snapshot, nowMs = 100_000L)
        assertEquals(ProactivityLevel.L1_MICRO_EXPRESSION, result.level)
        // Should stimulate EmotionEngine with mild awakening
        verify(atLeast = 1) { emotionEngine.applyStimulus(any()) }
    }

    @Test
    fun testCoordinate_proactiveInterrupt_producesL2BubbleAndConsumesQuota() = runTest {
        val snapshot = createSnapshot()
        val mockThought = UnspokenThoughtEntity(
            id = "thought-1",
            thought = "看你在桌面发呆，要不要聊聊天？",
            createdAt = 100_000L
        )
        coEvery { thoughtManager.processPerception(snapshot, any(), any()) } returns 4.8
        coEvery { thoughtManager.getTopPendingThoughts(1) } returns listOf(mockThought)
        every { decisionEngine.evaluate(snapshot, any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.PROACTIVE_INTERRUPT,
            innerMotivation = 4.8,
            interruptCost = 1.0,
            effectiveMotivation = 4.8,
            breakpoint = BreakpointResult(true, BreakpointType.RETURN_TO_LAUNCHER),
            reason = "Ready to interrupt"
        )

        val result = coordinator.coordinate(snapshot, nowMs = 100_000L)
        assertEquals(ProactivityLevel.L2_ICEBREAKER_BUBBLE, result.level)
        assertNotNull(result.thought)
        assertEquals("thought-1", result.thought?.id)
        assertEquals(1, result.bubblesShownToday)
    }

    @Test
    fun testCoordinate_dailyQuotaExhausted_downgradesToL1() = runTest {
        val snapshot = createSnapshot()
        val mockThought = UnspokenThoughtEntity(
            id = "thought-quota",
            thought = "想说的话",
            createdAt = 100_000L
        )
        coEvery { thoughtManager.processPerception(snapshot, any(), any()) } returns 4.8
        coEvery { thoughtManager.getTopPendingThoughts(1) } returns listOf(mockThought)
        every { decisionEngine.evaluate(snapshot, any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.PROACTIVE_INTERRUPT,
            innerMotivation = 4.8,
            interruptCost = 1.0,
            effectiveMotivation = 4.8,
            breakpoint = BreakpointResult(true, BreakpointType.RETURN_TO_LAUNCHER),
            reason = "Ready"
        )

        // Consume 2 quotas
        val r1 = coordinator.coordinate(snapshot, nowMs = 100_000L)
        assertEquals(ProactivityLevel.L2_ICEBREAKER_BUBBLE, r1.level)
        assertEquals(1, r1.bubblesShownToday)

        val r2 = coordinator.coordinate(snapshot, nowMs = 101_000L)
        assertEquals(ProactivityLevel.L2_ICEBREAKER_BUBBLE, r2.level)
        assertEquals(2, r2.bubblesShownToday)

        // 3rd attempt on same day must be rejected by quota limit (DAILY_BUBBLE_QUOTA = 2)
        val r3 = coordinator.coordinate(snapshot, nowMs = 102_000L)
        assertEquals(ProactivityLevel.L1_MICRO_EXPRESSION, r3.level)
        assertTrue("Reason should mention quota: ${r3.reason}", r3.reason.contains("配额"))
    }

    @Test
    fun testCoordinate_dailyQuotaExhausted_priorityWakeThoughtBypassesQuota() = runTest {
        val snapshot = createSnapshot()
        val normalThought = UnspokenThoughtEntity(
            id = "thought-normal",
            thought = "普通闲聊念头",
            urgency = 2.0f,
            createdAt = 100_000L
        )
        val priorityWakeThought = UnspokenThoughtEntity(
            id = "thought_wake_alarm_999",
            thought = "【自定约定】时间到了，提醒喝水",
            urgency = 4.8f,
            createdAt = 103_000L
        )

        coEvery { thoughtManager.processPerception(snapshot, any(), any()) } returns 4.8
        coEvery { thoughtManager.getTopPendingThoughts(1) } returns listOf(normalThought)
        every { decisionEngine.evaluate(snapshot, any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.PROACTIVE_INTERRUPT,
            innerMotivation = 4.8,
            interruptCost = 1.0,
            effectiveMotivation = 4.8,
            breakpoint = BreakpointResult(true, BreakpointType.RETURN_TO_LAUNCHER),
            reason = "Ready"
        )

        // 消耗完 2 次日常闲聊配额
        val r1 = coordinator.coordinate(snapshot, nowMs = 100_000L)
        assertEquals(ProactivityLevel.L2_ICEBREAKER_BUBBLE, r1.level)
        val r2 = coordinator.coordinate(snapshot, nowMs = 101_000L)
        assertEquals(ProactivityLevel.L2_ICEBREAKER_BUBBLE, r2.level)

        // 此时待决念头变为自定约定到期念头 (priorityWakeThought)
        coEvery { thoughtManager.getTopPendingThoughts(1) } returns listOf(priorityWakeThought)

        val r3 = coordinator.coordinate(snapshot, nowMs = 103_000L)
        assertEquals(
            "高优先级自定约定闹钟到期念头必须突破闲聊配额限制，展示 L2 气泡",
            ProactivityLevel.L2_ICEBREAKER_BUBBLE,
            r3.level
        )
        assertEquals("thought_wake_alarm_999", r3.thought?.id)
    }

    @Test
    fun testCoordinateFirstFrame_showsBubbleAndConsumesQuota() = runTest {
        val mockThought = UnspokenThoughtEntity(
            id = "thought-first-frame",
            thought = "首帧兜底念头",
            createdAt = 100_000L
        )
        coEvery { thoughtManager.getTopPendingThoughts(1) } returns listOf(mockThought)

        val result = coordinator.coordinateFirstFrame(nowMs = 100_000L)

        assertEquals("首帧兜底必须经协调器裁决并计入每日配额（评审中等#4）", ProactivityLevel.L2_ICEBREAKER_BUBBLE, result.level)
        assertEquals("thought-first-frame", result.thought?.id)
        assertEquals(1, result.bubblesShownToday)
        assertEquals(1, coordinator.getBubblesShownToday())
    }

    @Test
    fun testCoordinateFirstFrame_quotaExhausted_blocksBubble() = runTest {
        val mockThought = UnspokenThoughtEntity(
            id = "thought-quota-frame",
            thought = "想说的话",
            createdAt = 100_000L
        )
        coEvery { thoughtManager.getTopPendingThoughts(1) } returns listOf(mockThought)

        // 先用 coordinate 消耗完 2 次每日配额（decisionEngine stub 为允许打断）
        val snapshot = createSnapshot()
        coEvery { thoughtManager.processPerception(snapshot, any(), any()) } returns 4.8
        every { decisionEngine.evaluate(snapshot, any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.PROACTIVE_INTERRUPT,
            innerMotivation = 4.8,
            interruptCost = 1.0,
            effectiveMotivation = 4.8,
            breakpoint = BreakpointResult(true, BreakpointType.RETURN_TO_LAUNCHER),
            reason = "Ready"
        )
        coordinator.coordinate(snapshot, nowMs = 100_000L)
        coordinator.coordinate(snapshot, nowMs = 101_000L)
        assertEquals(2, coordinator.getBubblesShownToday())

        // 配额耗尽后首帧兜底同样必须被拦截，不得旁路
        val result = coordinator.coordinateFirstFrame(nowMs = 102_000L)
        assertEquals("配额耗尽时首帧兜底必须被拦截", ProactivityLevel.L0_SILENT, result.level)
        assertNull(result.thought)
        assertEquals(2, result.bubblesShownToday)
    }

    @Test
    fun testCoordinateFirstFrame_emptyThoughtPool_producesSilent() = runTest {
        // relaxed mockk 默认 getTopPendingThoughts 返回空列表
        val result = coordinator.coordinateFirstFrame(nowMs = 100_000L)
        assertEquals(ProactivityLevel.L0_SILENT, result.level)
        assertNull(result.thought)
        assertEquals(0, result.bubblesShownToday)
    }

    @Test
    fun testFeedback_dismissed_increasesBackoffPenalty() = runTest {
        val now = 100_000L
        val initialPenalty = coordinator.getBackoffPenalty()
        assertEquals(0.0, initialPenalty, 0.001)

        coordinator.onUserFeedback(UserFeedback.DISMISSED, thoughtId = "t-1", nowMs = now)

        val updatedPenalty = coordinator.getBackoffPenalty()
        assertTrue("Dismissed feedback should increase backoff penalty: $updatedPenalty", updatedPenalty > initialPenalty)
        coVerify(atLeast = 1) { thoughtManager.consumeThought("t-1", now) }
    }

    @Test
    fun testFeedback_clicked_consumesThoughtAndResetsSatiety() = runTest {
        val now = 100_000L
        coordinator.onUserFeedback(UserFeedback.CLICKED, thoughtId = "t-2", nowMs = now)

        coVerify(exactly = 1) { thoughtManager.consumeThought("t-2", now) }
        coVerify(exactly = 1) { thoughtManager.markExpressed(now) }
    }
}
