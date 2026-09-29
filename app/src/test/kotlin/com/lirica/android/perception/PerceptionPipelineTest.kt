package com.lirica.android.perception

import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.perception.model.AccessibilityNodeSnapshot
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.NotificationCategory
import com.lirica.android.perception.model.NotificationEvent
import com.lirica.android.perception.model.PageContentSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerceptionPipelineTest {

    private lateinit var pipeline: PerceptionPipeline
    private lateinit var privacyBreaker: PrivacyBreaker
    private lateinit var emotionEngine: EmotionEngine
    private lateinit var unspokenThoughtDao: UnspokenThoughtDao

    private val foregroundAppFlow = MutableStateFlow<ForegroundAppInfo?>(null)
    private val screenStateFlow = MutableStateFlow(ScreenStateSnapshot())
    private val notificationFlow = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 16)
    private val pageContentFlow = MutableSharedFlow<PageContentSnapshot>(extraBufferCapacity = 16)

    @Before
    fun setUp() {
        privacyBreaker = PrivacyBreaker()
        emotionEngine = spyk(EmotionEngine(baseline = EmotionVector(0.20f, 0.10f)))
        unspokenThoughtDao = mockk(relaxed = true)

        pipeline = PerceptionPipeline(
            foregroundAppFlow = foregroundAppFlow,
            screenStateFlow = screenStateFlow,
            notificationFlow = notificationFlow,
            pageContentFlow = pageContentFlow,
            privacyBreaker = privacyBreaker,
            emotionEngine = emotionEngine,
            unspokenThoughtDao = unspokenThoughtDao
        )
    }

    @Test
    fun testPipelineAggregation_combinesSourcesIntoUnifiedSnapshot() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        pipeline.start(scope = this, dispatcher = testDispatcher)

        // 2026-09-27 语义更新：微信主包已纳入感知整包熔断，「普通 App 聚合」
        // 反例改用中性包名；熔断路径由 testPipelineAppliesPrivacyBreaker_forSensitiveApp 覆盖
        // 1. Update foreground app
        foregroundAppFlow.value = ForegroundAppInfo(
            packageName = "com.example.notes",
            className = "NoteEditor",
            dwellDurationMs = 2000L
        )
        testScheduler.runCurrent()

        var snapshot = pipeline.snapshotFlow.value
        assertEquals("com.example.notes", snapshot.foregroundApp?.packageName)
        assertEquals(2000L, snapshot.foregroundApp?.dwellDurationMs)
        assertFalse(snapshot.isPrivacyFusing)

        // 2. Update screen state
        screenStateFlow.value = ScreenStateSnapshot(
            state = ScreenState.UNLOCKED,
            lastStateChangeTimestamp = 5000L,
            totalInteractiveDurationMs = 3000L
        )
        testScheduler.runCurrent()

        snapshot = pipeline.snapshotFlow.value
        assertEquals(ScreenState.UNLOCKED, snapshot.screenState.state)

        // 3. Emit notification
        val notif = NotificationEvent(
            id = "notif_1",
            packageName = "com.example.notes",
            title = "张三",
            content = "今晚聚餐",
            postTime = 6000L,
            category = NotificationCategory.COMMUNICATION
        )
        notificationFlow.tryEmit(notif)
        testScheduler.runCurrent()

        snapshot = pipeline.snapshotFlow.value
        assertEquals("今晚聚餐", snapshot.latestNotification?.content)

        // 4. Emit page content
        val page = PageContentSnapshot(
            packageName = "com.example.notes",
            className = "NoteEditor",
            nodes = listOf(
                AccessibilityNodeSnapshot(text = "好的，没问题")
            )
        )
        pageContentFlow.tryEmit(page)
        testScheduler.runCurrent()

        snapshot = pipeline.snapshotFlow.value
        assertEquals(1, snapshot.pageContent?.nodes?.size)
        assertEquals("好的，没问题", snapshot.pageContent?.nodes?.first()?.text)

        pipeline.stop()
    }

    @Test
    fun testPipelineAppliesPrivacyBreaker_forSensitiveApp() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        pipeline.start(scope = this, dispatcher = testDispatcher)

        // Enter Alipay
        foregroundAppFlow.value = ForegroundAppInfo(
            packageName = "com.eg.android.AlipayGphone",
            className = "BillActivity"
        )
        testScheduler.runCurrent()

        val alipayPage = PageContentSnapshot(
            packageName = "com.eg.android.AlipayGphone",
            className = "BillActivity",
            nodes = listOf(
                AccessibilityNodeSnapshot(text = "支付金额: ¥100.00")
            )
        )
        pageContentFlow.tryEmit(alipayPage)
        testScheduler.runCurrent()

        val snapshot = pipeline.snapshotFlow.value
        assertTrue("Snapshot should indicate privacy fusing is active", snapshot.isPrivacyFusing)
        assertTrue("Sensitive page nodes should be cleared", snapshot.pageContent?.nodes?.isEmpty() == true)

        pipeline.stop()
    }

    @Test
    fun testPipelineGeneratesUnspokenThought_onCommunicationNotification() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        pipeline.start(scope = this, dispatcher = testDispatcher)
        testScheduler.runCurrent() // 确保订阅者协程已就绪

        val notif = NotificationEvent(
            id = "wechat_msg",
            packageName = "com.tencent.mm",
            title = "妈妈",
            content = "天气转凉了，记得多穿件衣服。",
            postTime = 10_000L,
            category = NotificationCategory.COMMUNICATION
        )

        val thoughtSlot = slot<UnspokenThoughtEntity>()
        coEvery { unspokenThoughtDao.insert(capture(thoughtSlot)) } returns 1L

        notificationFlow.tryEmit(notif)
        testScheduler.runCurrent()

        coVerify(atLeast = 1) { unspokenThoughtDao.insert(any()) }
        assertTrue(thoughtSlot.isCaptured)
        val inserted = thoughtSlot.captured
        assertTrue(inserted.thought.contains("妈妈") || inserted.thought.contains("衣服"))
        assertEquals("com.tencent.mm", inserted.triggerContext)

        pipeline.stop()
    }

    @Test
    fun testPipelineStimulatesEmotionEngine_onScreenUnlocked() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        pipeline.start(scope = this, dispatcher = testDispatcher)

        screenStateFlow.value = ScreenStateSnapshot(state = ScreenState.UNLOCKED)
        testScheduler.runCurrent()

        io.mockk.verify(atLeast = 1) { emotionEngine.applyStimulus(any()) }

        pipeline.stop()
    }
}
