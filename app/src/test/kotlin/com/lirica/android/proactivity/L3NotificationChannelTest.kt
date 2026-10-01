package com.lirica.android.proactivity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.perception.model.HardwareEnvironmentState
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * L3 主动通知通道的接线断言（本轮修复的 P0）。
 *
 * 历史问题：L3_LIGHT_NOTIFICATION 只在枚举里存在，协调器从不返回它、
 * 派发器在生产代码里 0 调用方——息屏/锁屏时高优先事项没有任何送达通道。
 * 本测试从"协调器裁决"到"派发器被调用"到"念头被核销"逐环断言，防止再次退化成死代码。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class L3NotificationChannelTest {

    private lateinit var context: Context
    private lateinit var thoughtManager: UnspokenThoughtManager
    private lateinit var decisionEngine: SilenceDecisionEngine
    private lateinit var emotionEngine: EmotionEngine
    private lateinit var dispatcher: ProactiveNotificationDispatcher
    private var now = 10_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        thoughtManager = mockk(relaxed = true)
        decisionEngine = mockk(relaxed = true)
        emotionEngine = mockk(relaxed = true)
        dispatcher = spyk(
            ProactiveNotificationDispatcher(
                minCooldownMs = 30 * 60 * 1000L,
                timeProvider = { now }
            )
        )
        every { emotionEngine.currentVector } returns EmotionVector(0f, 0f)
    }

    private fun coordinator(): ProactiveBubbleCoordinator = ProactiveBubbleCoordinatorImpl(
        thoughtManager = thoughtManager,
        decisionEngine = decisionEngine,
        emotionEngine = emotionEngine,
        notificationDispatcher = dispatcher,
        contextProvider = { context },
        timeProvider = { now }
    )

    private fun screenOffSnapshot() = PerceptionSnapshot(
        timestamp = now,
        screenState = ScreenStateSnapshot(state = ScreenState.OFF, lastStateChangeTimestamp = now)
    )

    private fun unlockedSnapshot() = PerceptionSnapshot(
        timestamp = now,
        screenState = ScreenStateSnapshot(state = ScreenState.UNLOCKED, lastStateChangeTimestamp = now)
    )

    private fun urgentThought(id: String = "thought_wake_1", urgency: Float = 4.8f) = UnspokenThoughtEntity(
        id = id,
        thought = "期末答辩｜待会儿不是有「期末答辩」嘛，准备得怎么样啦？",
        urgency = urgency,
        createdAt = now,
        expiryTime = now + 3600_000L,
        status = UnspokenThoughtEntity.STATUS_PENDING
    )

    @Test
    fun testScreenOff_withUrgentThought_dispatchesL3AndConsumesThought() = runTest {
        val thought = urgentThought()
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns listOf(thought)
        coEvery { thoughtManager.getTopPendingThoughts(limit = 50) } returns listOf(thought)
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.INNER_THOUGHT_ONLY,
            innerMotivation = 4.8,
            interruptCost = 5.0,
            effectiveMotivation = 0.0,
            breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
            reason = "息屏禁止气泡"
        )

        val result = coordinator().coordinate(screenOffSnapshot(), now)

        assertEquals(ProactivityLevel.L3_LIGHT_NOTIFICATION, result.level)
        verify(exactly = 1) { dispatcher.dispatchNotification(context, "期末答辩", any(), any(), any()) }
        coVerify(exactly = 1) { thoughtManager.consumeThought(thought.id, now) }
    }

    @Test
    fun testScreenUnlocked_doesNotUseNotificationChannel() = runTest {
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns listOf(urgentThought())
        coEvery { thoughtManager.processPerception(any(), any(), any()) } returns 0.0
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.INNER_THOUGHT_ONLY,
            innerMotivation = 0.0,
            interruptCost = 2.0,
            effectiveMotivation = 0.0,
            breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
            reason = "动机不足"
        )

        val result = coordinator().coordinate(unlockedSnapshot(), now)

        assertTrue("解锁态不应走通知通道", result.level != ProactivityLevel.L3_LIGHT_NOTIFICATION)
        verify(exactly = 0) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testPrivacyFusing_neverDispatchesL3() = runTest {
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns listOf(urgentThought())
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.INNER_THOUGHT_ONLY,
            innerMotivation = 4.8,
            interruptCost = 5.0,
            effectiveMotivation = 0.0,
            breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
            reason = "隐私熔断"
        )
        val fusing = screenOffSnapshot().copy(isPrivacyFusing = true)

        val result = coordinator().coordinate(fusing, now)

        assertEquals(ProactivityLevel.L1_MICRO_EXPRESSION, result.level)
        verify(exactly = 0) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testInCall_neverDispatchesL3() = runTest {
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns listOf(urgentThought())
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.INNER_THOUGHT_ONLY,
            innerMotivation = 4.8,
            interruptCost = 5.0,
            effectiveMotivation = 0.0,
            breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
            reason = "通话中"
        )
        val inCall = screenOffSnapshot().copy(
            hardwareEnvironment = HardwareEnvironmentState(isPhoneInCall = true)
        )

        val result = coordinator().coordinate(inCall, now)

        assertEquals(ProactivityLevel.L1_MICRO_EXPRESSION, result.level)
        verify(exactly = 0) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testExpiredUrgentThought_isNotDelivered() = runTest {
        val expired = urgentThought().copy(expiryTime = now - 1L)
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns listOf(expired)
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.SILENT,
            innerMotivation = 0.0,
            interruptCost = 5.0,
            effectiveMotivation = 0.0,
            breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
            reason = "沉默"
        )

        val result = coordinator().coordinate(screenOffSnapshot(), now)

        assertEquals(ProactivityLevel.L0_SILENT, result.level)
        verify(exactly = 0) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testDispatchL3_dailyQuotaCapsDeliveries() = runTest {
        // 派发器冷却设为 0，单独检验"每日上限"这道闸（而不是被 30 分钟冷却掩盖）
        val noCooldown = spyk(ProactiveNotificationDispatcher(minCooldownMs = 0L, timeProvider = { now }))
        val coord = ProactiveBubbleCoordinatorImpl(
            thoughtManager = thoughtManager,
            decisionEngine = decisionEngine,
            emotionEngine = emotionEngine,
            notificationDispatcher = noCooldown,
            contextProvider = { context },
            timeProvider = { now }
        )
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.INNER_THOUGHT_ONLY,
            innerMotivation = 4.8,
            interruptCost = 5.0,
            effectiveMotivation = 0.0,
            breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
            reason = "息屏"
        )

        repeat(ProactiveBubbleCoordinator.DAILY_L3_NOTIFICATION_QUOTA + 2) { i ->
            coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
                listOf(urgentThought(id = "thought_wake_$i", urgency = 4.9f))
            coord.coordinate(screenOffSnapshot(), now + i * 1000L)
        }

        verify(exactly = ProactiveBubbleCoordinator.DAILY_L3_NOTIFICATION_QUOTA) {
            noCooldown.dispatchNotification(any(), any(), any(), any(), any())
        }
    }
}
