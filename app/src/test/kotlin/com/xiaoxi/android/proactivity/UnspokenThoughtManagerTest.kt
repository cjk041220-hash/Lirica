package com.xiaoxi.android.proactivity

import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.perception.model.ForegroundAppInfo
import com.xiaoxi.android.perception.model.NotificationCategory
import com.xiaoxi.android.perception.model.NotificationEvent
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.perception.model.ScreenState
import com.xiaoxi.android.perception.model.ScreenStateSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnspokenThoughtManagerTest {

    private lateinit var dao: UnspokenThoughtDao
    private lateinit var dynamics: MotivationDynamics
    private lateinit var synthesizer: ThoughtSynthesizer
    private lateinit var manager: UnspokenThoughtManager

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        dynamics = MotivationDynamicsImpl()
        synthesizer = ThoughtSynthesizerImpl()
        manager = UnspokenThoughtManagerImpl(dao, dynamics, synthesizer)
    }

    private fun createSnapshot(
        packageName: String = "com.android.launcher",
        screenState: ScreenState = ScreenState.UNLOCKED,
        dwellDurationMs: Long = 10_000L,
        notification: NotificationEvent? = null
    ): PerceptionSnapshot {
        return PerceptionSnapshot(
            timestamp = 100_000L,
            foregroundApp = ForegroundAppInfo(
                packageName = packageName,
                timestamp = 100_000L,
                dwellDurationMs = dwellDurationMs
            ),
            screenState = ScreenStateSnapshot(
                state = screenState,
                lastStateChangeTimestamp = 100_000L - dwellDurationMs,
                totalInteractiveDurationMs = dwellDurationMs
            ),
            latestNotification = notification,
            pageContent = null,
            isPrivacyFusing = false,
            perceivedSummary = "Normal state"
        )
    }

    // --- MotivationDynamics Tests ---

    @Test
    fun testMotivationDynamics_curiosityIncreasesOnNotificationAndDecays() {
        val notif = NotificationEvent(
            id = "notif-1",
            packageName = "com.tencent.mm",
            title = "张三",
            content = "在吗？",
            postTime = 100_000L,
            category = NotificationCategory.COMMUNICATION
        )
        val snapshotWithNotif = createSnapshot(notification = notif)
        val emotion = EmotionVector(0f, 0f)

        // Initial update with notification -> curiosity boosts
        val v1 = dynamics.update(snapshotWithNotif, emotion, deltaTimeMs = 0L)
        assertTrue("Curiosity should increase on notification: ${v1.curiosity}", v1.curiosity >= 1.0)

        // Time passes without new triggers -> curiosity leaks/decays
        val snapshotNormal = createSnapshot(notification = null)
        val v2 = dynamics.update(snapshotNormal, emotion, deltaTimeMs = 10 * 60 * 1000L) // 10 min
        assertTrue("Curiosity should decay over time: ${v2.curiosity} < ${v1.curiosity}", v2.curiosity < v1.curiosity)
    }

    @Test
    fun testMotivationDynamics_boredomAccumulatesOnIdleAndResetsOnActive() {
        val emotion = EmotionVector(0f, 0f)
        val idleSnapshot = createSnapshot(
            packageName = "com.android.launcher",
            dwellDurationMs = 60_000L // 1 minute in launcher
        )

        // 10 minutes of idle
        var currentVector = MotivationVector()
        repeat(5) {
            currentVector = dynamics.update(idleSnapshot, emotion, deltaTimeMs = 2 * 60 * 1000L)
        }
        assertTrue("Boredom should accumulate on prolonged idle: ${currentVector.boredom}", currentVector.boredom > 0.5)

        // User actively switches app
        val activeSnapshot = createSnapshot(
            packageName = "com.zhihu.android",
            dwellDurationMs = 1_000L
        )
        val resetVector = dynamics.update(activeSnapshot, emotion, deltaTimeMs = 1000L)
        assertTrue("Boredom should reset on active app switch: ${resetVector.boredom}", resetVector.boredom < currentVector.boredom)
    }

    @Test
    fun testMotivationDynamics_warmthAccumulatesOnLateNightOrLongDwell() {
        val emotion = EmotionVector(valence = -0.3f, arousal = -0.2f) // negative/tired emotion
        val tiredSnapshot = createSnapshot(
            packageName = "com.tencent.mm",
            dwellDurationMs = 45 * 60 * 1000L // 45 min continuous usage
        )

        val v1 = dynamics.update(tiredSnapshot, emotion, deltaTimeMs = 60_000L)
        assertTrue("Warmth should accumulate on long dwell + negative emotion: ${v1.warmth}", v1.warmth > 0.5)
    }

    @Test
    fun testMotivationDynamics_urgencySpikesOnHighPriorityNotification() {
        val scheduleNotif = NotificationEvent(
            id = "notif-2",
            packageName = "com.google.android.calendar",
            title = "会议提醒",
            content = "项目评审将在10分钟后开始",
            postTime = 100_000L,
            category = NotificationCategory.SCHEDULE
        )
        val snapshot = createSnapshot(notification = scheduleNotif)
        val emotion = EmotionVector(0f, 0f)

        val v = dynamics.update(snapshot, emotion, deltaTimeMs = 0L)
        assertTrue("Urgency should spike on schedule notification: ${v.urgency}", v.urgency >= 2.0)
    }

    @Test
    fun testMotivationDynamics_satietyAndCooldownSuppressesMotivation() {
        val notif = NotificationEvent(
            id = "notif-3",
            packageName = "com.tencent.mm",
            title = "李四",
            content = "晚上一起吃饭吗",
            postTime = 100_000L,
            category = NotificationCategory.COMMUNICATION
        )
        val snapshot = createSnapshot(notification = notif)
        val emotion = EmotionVector(0f, 0f)

        val now = 100_000L
        val vBefore = dynamics.update(snapshot, emotion, deltaTimeMs = 0L)
        val mBefore = dynamics.computeTotalMotivation(vBefore, now)
        assertTrue("Motivation before expression should be elevated: $mBefore", mBefore > 1.0)

        // Express thought (Satiety triggers)
        dynamics.onExpressed(now)

        // Immediately after, motivation should be reset/suppressed
        val vAfter = dynamics.getCurrentVector()
        assertEquals(0.0, vAfter.curiosity, 0.001)
        assertEquals(0.0, vAfter.warmth, 0.001)
        val mAfter = dynamics.computeTotalMotivation(vAfter, now)
        assertEquals("Motivation after expression should be 0.0", 0.0, mAfter, 0.01)

        // During cooldown (e.g. 10 mins later), motivation should be capped/suppressed
        val vCooldown = dynamics.update(snapshot, emotion, deltaTimeMs = 10 * 60 * 1000L)
        val mCooldown = dynamics.computeTotalMotivation(vCooldown, now + 10 * 60 * 1000L)
        assertTrue("During cooldown, motivation should remain suppressed: $mCooldown < $mBefore", mCooldown < mBefore)
    }

    // --- ThoughtSynthesizer Tests ---

    @Test
    fun testThoughtSynthesizer_generatesThoughtForNotification() {
        val notif = NotificationEvent(
            id = "notif-4",
            packageName = "com.sankuai.meituan",
            title = "美团外卖",
            content = "骑手已接单，预计20分钟送达",
            postTime = 100_000L,
            category = NotificationCategory.TRAVEL_LOGISTICS
        )
        val snapshot = createSnapshot(notification = notif)
        val emotion = EmotionVector(0.2f, 0.3f)

        val thought = synthesizer.synthesize(snapshot, emotion, motivationScore = 4.0)
        assertNotNull(thought)
        assertTrue("Thought should reflect takeaway: ${thought!!.thought}", thought.thought.contains("外卖") || thought.thought.contains("骑手"))
        assertEquals("pending", thought.status)
        assertTrue("Expiry time should be set in future", thought.expiryTime != null && thought.expiryTime!! > snapshot.timestamp)
    }

    @Test
    fun testThoughtSynthesizer_generatesThoughtForLongDwellWarmth() {
        val snapshot = createSnapshot(
            packageName = "com.tencent.mm",
            dwellDurationMs = 50 * 60 * 1000L // 50 mins
        )
        val emotion = EmotionVector(-0.2f, -0.3f)

        val thought = synthesizer.synthesize(snapshot, emotion, motivationScore = 3.8)
        assertNotNull(thought)
        assertTrue("Thought should express care/warmth: ${thought!!.thought}", thought.thought.contains("休息") || thought.thought.contains("忙"))
    }

    // --- UnspokenThoughtManager Tests ---

    @Test
    fun testManager_processPerception_insertsThoughtWhenMotivationSufficient() = runTest {
        val notif = NotificationEvent(
            id = "notif-5",
            packageName = "com.tencent.mm",
            title = "王五",
            content = "文件发给你了",
            postTime = 100_000L,
            category = NotificationCategory.COMMUNICATION
        )
        val snapshot = createSnapshot(notification = notif)
        val emotion = EmotionVector(0f, 0f)

        val slot = slot<UnspokenThoughtEntity>()
        coEvery { dao.insert(capture(slot)) } returns 1L

        val motivation = manager.processPerception(snapshot, emotion, nowMs = 100_000L)
        assertTrue("Motivation should be > 0: $motivation", motivation > 0.0)

        // Thought should be persisted to dao
        coVerify(atLeast = 1) { dao.insert(any()) }
        assertTrue(slot.isCaptured)
        assertEquals("pending", slot.captured.status)
    }

    @Test
    fun testManager_cleanupExpired_callsDaoDiscard() = runTest {
        val now = 200_000L
        coEvery { dao.discardExpiredThoughts(now) } returns 3

        val discarded = manager.cleanupExpired(now)
        assertEquals(3, discarded)
        coVerify(exactly = 1) { dao.discardExpiredThoughts(now) }
    }

    @Test
    fun testManager_consumeThought_callsDaoMarkConsumed() = runTest {
        val now = 200_000L
        manager.consumeThought("thought-123", now)

        coVerify(exactly = 1) { dao.markConsumed("thought-123", now) }
    }
}
