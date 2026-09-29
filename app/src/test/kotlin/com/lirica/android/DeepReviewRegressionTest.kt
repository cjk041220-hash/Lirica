package com.lirica.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.automation.healing.HealingAction
import com.lirica.android.automation.healing.SelfHealingWatchdogImpl
import com.lirica.android.automation.healing.WatchdogVerdict
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.network.ToolCallAssembler
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.NotificationCategory
import com.lirica.android.perception.model.NotificationEvent
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.persona.CharacterCardDecoder
import com.lirica.android.proactivity.DiaryEngineImpl
import com.lirica.android.proactivity.ThoughtSynthesizerImpl
import com.lirica.android.ui.chat.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DeepReviewRegressionTest {

    private lateinit var db: LiricaDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiricaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testDualTrackRecallSelfExperiences_supplementsFromActiveExcludingArchived() = runBlocking {
        val dao = db.selfExperienceDao()
        val manager = DualTrackMemoryManager(db)

        // Insert 1 fresh experience
        dao.insert(
            SelfExperienceEntity(
                id = "exp-1",
                title = "新鲜经历",
                content = "今天去公园散步了",
                domainTag = "生活",
                createdAt = 1000L,
                status = SelfExperienceEntity.STATUS_FRESH
            )
        )

        // Insert 1 shared experience in another domain
        dao.insert(
            SelfExperienceEntity(
                id = "exp-2",
                title = "已分享经历",
                content = "学习了新的 Kotlin 特性",
                domainTag = "技术学习",
                createdAt = 900L,
                status = SelfExperienceEntity.STATUS_SHARED
            )
        )

        // Insert 1 archived experience (should be excluded)
        dao.insert(
            SelfExperienceEntity(
                id = "exp-3",
                title = "归档经历",
                content = "很久以前的旧记忆",
                domainTag = "日常感悟",
                createdAt = 800L,
                status = SelfExperienceEntity.STATUS_ARCHIVED
            )
        )

        // When unsharedOnly = false, should return fresh + active shared (excluding archived)
        val recalled = manager.recallSelfExperiences(limit = 3, unsharedOnly = false)
        assertEquals(2, recalled.size)
        assertTrue(recalled.any { it.id == "exp-1" })
        assertTrue(recalled.any { it.id == "exp-2" })
        assertFalse(recalled.any { it.id == "exp-3" })
    }

    @Test
    fun testWatchdog_permissionAndAgreementKeywords_doNotTriggerPopupDismissal() {
        val watchdog = SelfHealingWatchdogImpl()
        watchdog.inspectStep(100L, "com.some.app", "com.some.app", "Start")

        // Page with permission / agreement keywords ("同意", "允许", "确定", "allow", "ok")
        val pageTexts = listOf("隐私政策", "请阅读并同意", "同意", "允许", "确定", "allow", "ok")
        val verdict = watchdog.inspectStep(101L, "com.some.app", "com.some.app", "Prompt", pageTexts)

        // Should NOT trigger POPUP_BLOCKING because agreement keywords are removed
        assertTrue("Agreement keywords must not trigger popup auto-dismissal", verdict is WatchdogVerdict.NormalProgress)
    }

    @Test
    fun testWatchdog_systemPermissionController_neverAutoDismissed() {
        val watchdog = SelfHealingWatchdogImpl()
        watchdog.inspectStep(200L, "com.some.app", "com.some.app", "Start")

        // System permission dialog with "关闭" or "拒绝"
        val pageTexts = listOf("请求相机权限", "拒绝", "关闭")
        val verdict = watchdog.inspectStep(
            fingerprint = 201L,
            currentPackage = "com.google.android.permissioncontroller",
            targetPackage = "com.some.app",
            actionSummary = "Request Permission",
            pageTexts = pageTexts
        )

        // System permission package must be strictly immune from auto-dismissal
        assertTrue("Permission controller must never be auto-dismissed", verdict is WatchdogVerdict.NormalProgress)
    }

    @Test
    fun testCharacterCardDecoder_exceedingChunkLimit_throwsIllegalArgumentException() {
        val baos = ByteArrayOutputStream()
        // PNG signature
        baos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val dos = DataOutputStream(baos)
        // Write chunk length that exceeds 8MB (e.g. 10MB)
        dos.writeInt(10 * 1024 * 1024)
        dos.write("tEXt".toByteArray(Charsets.US_ASCII))

        try {
            CharacterCardDecoder.decodeFromPng(baos.toByteArray())
            fail("Should throw IllegalArgumentException when chunk length exceeds 8MB")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exceeds limit") == true)
        }
    }

    @Test
    fun testDiaryEngine_idempotentSameDayGeneration() = runBlocking {
        val selfExpDao = db.selfExperienceDao()
        val chatDao = db.chatDialogueDao()
        val engine = DiaryEngineImpl(selfExpDao, chatDao)

        val fixedTs = 1710000000000L // arbitrary fixed timestamp
        val entry1 = engine.generateDailyDiary(fixedTs)
        val entry2 = engine.generateDailyDiary(fixedTs)

        // Same day must have the same deterministic ID
        assertEquals(entry1.id, entry2.id)
        assertTrue(entry1.id.startsWith("diary_"))

        // Database must contain only 1 entry for this day
        val freshEntries = engine.getFreshExperiences(10)
        assertEquals(1, freshEntries.size)
        assertEquals(entry1.id, freshEntries[0].id)
    }

    @Test
    fun testToolCallAssembler_clampsOutOfRangeIndexAndFiltersBlankNames() {
        val assembler = ToolCallAssembler()

        // Negative index should be ignored
        assembler.appendChunk(
            LlmStreamEvent.ToolCallChunk(
                index = -1,
                id = "call_bad",
                name = "bad_call",
                argumentsDelta = "{}"
            )
        )

        // Index > 32 should be ignored
        assembler.appendChunk(
            LlmStreamEvent.ToolCallChunk(
                index = 99,
                id = "call_overflow",
                name = "overflow_call",
                argumentsDelta = "{}"
            )
        )

        // Valid chunk with blank name should be filtered out
        assembler.appendChunk(
            LlmStreamEvent.ToolCallChunk(
                index = 1,
                id = "call_empty_name",
                name = "   ",
                argumentsDelta = "{}"
            )
        )

        // Valid chunk
        assembler.appendChunk(
            LlmStreamEvent.ToolCallChunk(
                index = 0,
                id = "call_ok",
                name = "open_settings",
                argumentsDelta = "{\"tab\":\"network\"}"
            )
        )

        val completed = assembler.getCompletedToolCalls()
        assertEquals(1, completed.size)
        assertEquals("open_settings", completed[0].name)
    }

    @Test
    fun testThoughtSynthesizer_deduplicatesSameNotificationAcrossFrames() {
        val synthesizer = ThoughtSynthesizerImpl()
        val notif = NotificationEvent(
            id = "notif-unique-1",
            packageName = "com.tencent.mm",
            title = "朋友",
            content = "今天聚会吗？",
            postTime = 1000L,
            category = NotificationCategory.COMMUNICATION
        )

        val snapshot = PerceptionSnapshot(
            timestamp = 1000L,
            latestNotification = notif
        )
        val emotion = EmotionVector(0f, 0f)

        // First frame: should synthesize thought
        val thought1 = synthesizer.synthesize(snapshot, emotion, motivationScore = 2.0)
        assertNotNull(thought1)
        assertTrue(thought1!!.thought.contains("朋友") || thought1.thought.contains("新消息"))

        // Second frame: same snapshot (notification ID is the same), should NOT re-synthesize notification thought
        val thought2 = synthesizer.synthesize(snapshot, emotion, motivationScore = 2.0)
        assertNull("Same notification must not be synthesized repeatedly across frames", thought2)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testChatViewModel_cancellationException_rethrowsWithoutSettingErrorMessage() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val mockLlmClient = mockk<LiricaLlmClient>()
            every { mockLlmClient.streamChat(any(), any(), any()) } returns flow {
                throw CancellationException("ViewModel coroutine cancelled")
            }

            val mockMemoryManager = mockk<DualTrackMemoryManager>(relaxed = true)
            io.mockk.coEvery { mockMemoryManager.recallUserMemories(any(), any(), any(), any()) } returns emptyList()
            io.mockk.coEvery { mockMemoryManager.recallSelfExperiences(any(), any()) } returns emptyList()
            io.mockk.coEvery { mockMemoryManager.recallUnspokenThoughts(any(), any()) } returns emptyList()
            io.mockk.coEvery { mockMemoryManager.assembleDynamicContext(any(), any(), any(), any()) } returns null

            val emotionEngine = com.lirica.android.emotion.EmotionEngine()
            val vm = ChatViewModel(
                llmClient = mockLlmClient,
                memoryManager = mockMemoryManager,
                emotionEngine = emotionEngine
            )

            vm.sendMessage("你好")
            testScheduler.advanceUntilIdle()

            // Error message in UI state must NOT be set to "通信异常" on cancellation
            assertNull("CancellationException must not set errorMessage", vm.uiState.value.errorMessage)
            assertFalse("Streaming must be reset to false on cancellation", vm.uiState.value.isStreaming)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
