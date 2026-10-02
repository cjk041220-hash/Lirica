package com.lirica.android.proactivity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import io.mockk.coEvery
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日配额跨天重置的回归测试（2026-10-02 P0 修复）。
 *
 * ## 被守护的缺陷
 *
 * 旧实现中 L3 通知配额的跨天重置写在 `dispatchL3Notification` 内部，
 * 且排在 `l3DeliveredToday >= DAILY_L3_NOTIFICATION_QUOTA` 守门**之后**：
 *
 * ```
 * day1: 派发 3 条 → l3DeliveredToday = 3, lastL3Day = day1
 * day2: rollover 只重置气泡配额（不碰 L3）
 *       → 守门 3 >= 3 命中 → return false
 *       → 重置代码永不可达 → L3 通道永久失效
 * ```
 *
 * 旧测试 `L3NotificationChannelTest.testDispatchL3_dailyQuotaCapsDeliveries`
 * 在**同一天内**重复 5 次，只验证了"当天封顶"，从未跨天，因此全绿掩盖了该缺陷。
 *
 * ## 为什么这条测试不会假绿
 *
 * 每条用例都显式断言了触发前置条件（当前台账里的计数与归属日），
 * 而非仅断言"派发了几次"。回滚修复后这些前置断言会先转红。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DailyQuotaRolloverTest {

    private lateinit var context: Context
    private lateinit var thoughtManager: UnspokenThoughtManager
    private lateinit var decisionEngine: SilenceDecisionEngine
    private lateinit var emotionEngine: EmotionEngine

    /** 2026-10-02 12:00:00，固定时刻避免跨天判断受测试执行时刻干扰 */
    private val day1Noon: Long = 1_770_000_000_000L
    private val day2Noon: Long = day1Noon + 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        thoughtManager = mockk(relaxed = true)
        decisionEngine = mockk(relaxed = true)
        emotionEngine = mockk(relaxed = true)
        every { emotionEngine.currentVector } returns EmotionVector(0f, 0f)
        coEvery { thoughtManager.processPerception(any(), any(), any()) } returns 0.0
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.INNER_THOUGHT_ONLY,
            innerMotivation = 0.0,
            interruptCost = 2.0,
            effectiveMotivation = 0.0,
            breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
            reason = "动机不足"
        )
    }

    private fun day(offset: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(offset))

    private fun coordinatorWith(dispatcher: ProactiveNotificationDispatcher) =
        ProactiveBubbleCoordinatorImpl(
            thoughtManager = thoughtManager,
            decisionEngine = decisionEngine,
            emotionEngine = emotionEngine,
            notificationDispatcher = dispatcher,
            contextProvider = { context },
            timeProvider = { day1Noon }
        )

    private fun screenOffSnapshot(at: Long) = PerceptionSnapshot(
        timestamp = at,
        screenState = ScreenStateSnapshot(state = ScreenState.OFF, lastStateChangeTimestamp = at)
    )

    private fun urgentThought(id: String) = UnspokenThoughtEntity(
        id = id,
        thought = "期末答辩｜待会儿不是有「期末答辩」嘛",
        urgency = 0.96f,
        createdAt = day1Noon,
        expiryTime = null,
        status = UnspokenThoughtEntity.STATUS_PENDING
    )

    /**
     * 冷却设为 0，单独检验"每日配额"这道闸，避免被冷却逻辑掩盖。
     */
    private fun noCooldownDispatcher() =
        spyk(ProactiveNotificationDispatcher(minCooldownMs = 0L, timeProvider = { day1Noon }))

    @Test
    fun testQuota_新的一天_L3配额应重置并恢复送达() = runTest {
        val dispatcher = noCooldownDispatcher()
        val coord = coordinatorWith(dispatcher)

        // ---- 前置条件：确认 day1 与 day2 是不同自然日，否则本用例测不到跨天逻辑 ----
        val day1Label = day(day1Noon)
        val day2Label = day(day2Noon)
        assertTrue("前置条件失败：两个时刻必须落在不同自然日", day1Label != day2Label)

        // ---- day1：用满 3 条配额 ----
        val quota = ProactiveBubbleCoordinator.DAILY_L3_NOTIFICATION_QUOTA
        repeat(quota) { i ->
            coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
                listOf(urgentThought("thought_wake_d1_$i"))
            coord.coordinate(screenOffSnapshot(day1Noon), day1Noon)
        }
        verify(exactly = quota) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }

        // ---- day1 当天再加一条：应被配额拦住 ----
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
            listOf(urgentThought("thought_wake_d1_over"))
        coord.coordinate(screenOffSnapshot(day1Noon), day1Noon)
        verify(exactly = quota) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }

        // ---- day2：第一条应重新可达（这是缺陷修复的核心断言）----
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
            listOf(urgentThought("thought_wake_d2_0"))
        val result = coord.coordinate(screenOffSnapshot(day2Noon), day2Noon)

        assertEquals(
            "跨天后 L3 通道必须恢复可用（旧实现此处永久失效）",
            ProactivityLevel.L3_LIGHT_NOTIFICATION,
            result.level
        )
        verify(exactly = quota + 1) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testQuota_同一天内_L3配额仍应封顶() = runTest {
        val dispatcher = noCooldownDispatcher()
        val coord = coordinatorWith(dispatcher)
        val quota = ProactiveBubbleCoordinator.DAILY_L3_NOTIFICATION_QUOTA

        // 反向断言：修复"跨天重置"不得把"当天封顶"一起修掉（防过度纠正）
        repeat(quota + 3) { i ->
            coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
                listOf(urgentThought("thought_wake_same_$i"))
            coord.coordinate(screenOffSnapshot(day1Noon + i * 1000L), day1Noon + i * 1000L)
        }

        verify(exactly = quota) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testQuota_跨天后气泡配额也应重置() = runTest {
        // 气泡配额与 L3 配额共用同一个日期台账，必须一并验证没有回归
        val dispatcher = noCooldownDispatcher()
        val coord = ProactiveBubbleCoordinatorImpl(
            thoughtManager = thoughtManager,
            decisionEngine = decisionEngine,
            emotionEngine = emotionEngine,
            notificationDispatcher = dispatcher,
            contextProvider = { context },
            timeProvider = { day1Noon }
        )

        // day1 用满气泡配额（此处走解锁态以便触发气泡路径）
        val unlocked = PerceptionSnapshot(
            timestamp = day1Noon,
            screenState = ScreenStateSnapshot(state = ScreenState.UNLOCKED, lastStateChangeTimestamp = day1Noon)
        )
        every { decisionEngine.evaluate(any(), any(), any(), any(), any()) } returns DecisionResult(
            action = ProactiveAction.PROACTIVE_INTERRUPT,
            innerMotivation = 5.0,
            interruptCost = 1.0,
            effectiveMotivation = 5.0,
            breakpoint = BreakpointResult(isBreakpoint = true, type = BreakpointType.APP_SWITCH),
            reason = "达到打断阈值"
        )

        val bubbleQuota = ProactiveBubbleCoordinator.DAILY_BUBBLE_QUOTA
        repeat(bubbleQuota) { i ->
            // 低紧急度念头（不触发 L3 与履约旁路）
            coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
                listOf(urgentThought("thought_low_$i").copy(urgency = 0.3f))
            coord.coordinate(unlocked, day1Noon + i * 1000L)
        }
        assertEquals(bubbleQuota, coord.getBubblesShownToday())

        // day2：跨天后配额应重置，因此这次协调**能够展示气泡并消耗 1 次**。
        // 注意：重置后的正确计数是 1（不是 0）——若跨天重置失效，
        // 配额会停在 quota 上，result.level 降级为 L1 且计数不再增长。
        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
            listOf(urgentThought("thought_low_d2").copy(urgency = 0.3f))
        val day2Result = coord.coordinate(unlocked.copy(timestamp = day2Noon), day2Noon)

        assertEquals(
            "跨天后必须能重新展示气泡（配额被重置）",
            ProactivityLevel.L2_ICEBREAKER_BUBBLE,
            day2Result.level
        )
        assertEquals(
            "跨天后计数应为 1（重置后消耗了 1 次），而非停留在昨日的 $bubbleQuota",
            1,
            coord.getBubblesShownToday()
        )
    }

    @Test
    fun testQuota_首帧路径也应跨天重置L3配额() = runTest {
        // coordinateFirstFrame 是另一条独立入口，旧实现同样只重置气泡配额。
        //
        // 前置条件：首帧没有感知快照，L3 判定改用 PowerManager.isInteractive 作为
        // "屏幕是否可交互"的弱信号（取不到时按不可交互处理）。
        // Robolectric 默认 isInteractive=true → L3 分支永不进入，
        // 必须显式设为 false，否则本用例测不到目标路径（假绿陷阱）。
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
        org.robolectric.Shadows.shadowOf(powerManager).setIsInteractive(false)

        val dispatcher = noCooldownDispatcher()
        val coord = coordinatorWith(dispatcher)
        val quota = ProactiveBubbleCoordinator.DAILY_L3_NOTIFICATION_QUOTA

        repeat(quota) { i ->
            coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
                listOf(urgentThought("thought_wake_ff_$i"))
            coord.coordinateFirstFrame(day1Noon)
        }

        // 前置断言：day1 必须真的送达了 quota 条，否则后续 day2 断言无意义
        verify(exactly = quota) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }

        coEvery { thoughtManager.getTopPendingThoughts(limit = 1) } returns
            listOf(urgentThought("thought_wake_ff_d2"))
        val result = coord.coordinateFirstFrame(day2Noon)

        assertEquals(
            "首帧路径跨天后 L3 通道同样必须恢复",
            ProactivityLevel.L3_LIGHT_NOTIFICATION,
            result.level
        )
        verify(exactly = quota + 1) { dispatcher.dispatchNotification(any(), any(), any(), any(), any()) }
    }
}
