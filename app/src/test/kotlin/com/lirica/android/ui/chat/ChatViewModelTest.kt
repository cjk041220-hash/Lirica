package com.lirica.android.ui.chat

import app.cash.turbine.test
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.dao.ScheduledAlarmDao
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.life.CareEvent
import com.lirica.android.life.CareHintDetector
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import com.lirica.android.persona.model.CharacterCard
import io.mockk.slot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockLlmClient = mockk<LiricaLlmClient>()
    private val mockDatabase = mockk<LiricaDatabase>(relaxed = true)
    private val mockChatDialogueDao = mockk<ChatDialogueDao>(relaxed = true)
    private val mockMemoryManager = mockk<DualTrackMemoryManager>(relaxed = true)

    private lateinit var emotionEngine: EmotionEngine
    private lateinit var phiMiddleware: PhiMiddleware
    private lateinit var humannessFilter: HumannessFilter
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        emotionEngine = EmotionEngine(baseline = MoodState.CALM.defaultVector)
        phiMiddleware = PhiMiddleware()
        humannessFilter = HumannessFilter()

        coEvery { mockChatDialogueDao.getRecentBySession(any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.retrieve(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.recallUserMemories(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.recallSelfExperiences(any(), any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.recallSelfExperiences(any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.recallUnspokenThoughts(any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.assembleDynamicContext(any(), any(), any(), any()) } returns null

        viewModel = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = mockChatDialogueDao
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(MoodState.CALM, state.currentMood)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isStreaming)
        assertNull(state.errorMessage)
        assertFalse(state.isMoodDebugPanelVisible)
        assertFalse(state.isCharacterCardDialogOpen)
    }

    @Test
    fun testSendMessageStreamingAccumulation() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flow {
            emit(LlmStreamEvent.Token("今天天气"))
            emit(LlmStreamEvent.Token("真不错呀，"))
            emit(LlmStreamEvent.Token("一起出去散步吧！"))
            emit(LlmStreamEvent.Done("stop"))
        }

        viewModel.uiState.test {
            assertEquals(0, awaitItem().messages.size)

            viewModel.sendMessage("今天天气怎么样？")

            // First emission: user message added & assistant message placeholder added
            var item = awaitItem()
            while (item.messages.size < 2) {
                item = awaitItem()
            }
            assertEquals(2, item.messages.size)
            assertEquals("今天天气怎么样？", item.messages[0].text)
            assertEquals(MessageSender.USER, item.messages[0].sender)
            assertEquals(MessageSender.ASSISTANT, item.messages[1].sender)
            assertTrue(item.isStreaming)

            // Streaming tokens accumulation
            testScheduler.advanceUntilIdle()

            val finalState = viewModel.uiState.value
            assertFalse(finalState.isStreaming)
            assertEquals(2, finalState.messages.size)
            assertEquals("今天天气真不错呀，一起出去散步吧！", finalState.messages[1].text)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { mockChatDialogueDao.insert(any()) }
    }

    @Test
    fun testMemoryBadgeAssociation() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()

        val mockUserMemory = UserMemoryEntity(
            id = "mem_1",
            content = "用户喜欢在晴天散步",
            category = "爱好",
            confidence = 1.0f,
            importance = 9,
            createdAt = System.currentTimeMillis()
        )
        val mockExperience = SelfExperienceEntity(
            id = "exp_1",
            title = "白鸽见闻",
            content = "今天在公园看到一只可爱的白鸽",
            domainTag = "见闻",
            createdAt = System.currentTimeMillis()
        )
        val mockThought = UnspokenThoughtEntity(
            id = "th_1",
            thought = "想和用户聊聊公园的事",
            urgency = 0.8f,
            createdAt = System.currentTimeMillis(),
            expiryTime = System.currentTimeMillis() + 3600_000
        )

        coEvery { mockMemoryManager.retrieve(any(), any(), any(), any()) } returns listOf(mockUserMemory)
        coEvery { mockMemoryManager.recallUserMemories(any(), any(), any(), any()) } returns listOf(mockUserMemory)
        coEvery { mockMemoryManager.recallSelfExperiences(any(), any(), any()) } returns listOf(mockExperience)
        coEvery { mockMemoryManager.recallSelfExperiences(any(), any()) } returns listOf(mockExperience)
        coEvery { mockMemoryManager.recallUnspokenThoughts(any(), any()) } returns listOf(mockThought)

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flowOf(
            LlmStreamEvent.Token("真巧！"),
            LlmStreamEvent.Done("stop")
        )

        viewModel.sendMessage("去散步")
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.messages.size)
        val assistantMsg = state.messages[1]
        assertNotNull(assistantMsg.memoryBadge)
        assertEquals(listOf("用户喜欢在晴天散步"), assistantMsg.memoryBadge?.userMemories)
        assertEquals(listOf("今天在公园看到一只可爱的白鸽"), assistantMsg.memoryBadge?.selfExperiences)
        assertEquals(listOf("想和用户聊聊公园的事"), assistantMsg.memoryBadge?.thoughts)
        assertEquals(3, assistantMsg.memoryBadge?.totalCount)
    }

    @Test
    fun testEmotionEngineStateFlowReflection() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()
        assertEquals(MoodState.CALM, viewModel.uiState.value.currentMood)

        // Stimulus pushing to HAPPY: high valence & high arousal
        emotionEngine.applyStimulus(EmotionVector(0.9f, 0.6f))
        testScheduler.advanceUntilIdle()

        assertEquals(MoodState.HAPPY, viewModel.uiState.value.currentMood)
    }

    @Test
    fun testCharacterCardImport() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.activeCharacterCard)

        val card = CharacterCard(
            name = "测试 Lirica",
            description = "测试伴生助手",
            personality = "温柔活泼"
        )
        viewModel.importCharacterCard(card)
        testScheduler.advanceUntilIdle()

        assertEquals("测试 Lirica", viewModel.uiState.value.activeCharacterCard?.name)
    }

    @Test
    fun testErrorHandling() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flow {
            emit(LlmStreamEvent.Error(RuntimeException("Connection timeout")))
        }

        viewModel.sendMessage("Hello")
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Connection timeout"))
    }

    @Test
    fun testCareHintWithScheduledExamCombo() = runTest(testDispatcher) {
        val mockAlarmDao = mockk<ScheduledAlarmDao>(relaxed = true)
        val mockCareDetector = mockk<CareHintDetector>()

        val now = System.currentTimeMillis()
        val alarm = ScheduledAlarmEntity(
            id = "alarm_1",
            triggerAt = now + 3600_000L,
            botNote = "明天考高数",
            status = "pending"
        )
        coEvery { mockAlarmDao.getUpcomingAlarms(any(), any(), any()) } returns listOf(alarm)
        coEvery { mockChatDialogueDao.getUserTimestampsSince(any(), any()) } returns emptyList()
        coEvery { mockChatDialogueDao.getLastUserTimestampBefore(any()) } returns null

        val eventSlot = slot<List<CareEvent>>()
        every {
            mockCareDetector.collectHintTexts(
                now = any(),
                recentUserMessageTimes = any(),
                activeEvents = capture(eventSlot),
                lastUserMessageTime = any()
            )
        } returns listOf("明天有「明天考高数」，还没睡")

        val vm = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = mockChatDialogueDao,
            careHintDetector = mockCareDetector,
            scheduledAlarmDao = mockAlarmDao
        )

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flowOf(
            LlmStreamEvent.Token("早点睡哦")
        )

        vm.sendMessage("还在看书呢")
        testScheduler.advanceUntilIdle()

        assertTrue(eventSlot.isCaptured)
        val events = eventSlot.captured
        assertEquals(1, events.size)
        assertEquals("exam", events[0].eventType)
        assertEquals("明天考高数", events[0].title)
    }

    @Test
    fun testSendMessage_usesHybridMemoryRetrieveAndPassesPerception() = runTest(testDispatcher) {
        val mockPerception = mockk<com.lirica.android.perception.PerceptionPipeline>()
        val mockSnapshot = com.lirica.android.perception.model.PerceptionSnapshot(
            foregroundApp = com.lirica.android.perception.model.ForegroundAppInfo(
                packageName = "tv.danmaku.bili",
                dwellDurationMs = 120_000L
            )
        )
        every { mockPerception.snapshotFlow } returns kotlinx.coroutines.flow.MutableStateFlow(mockSnapshot)

        val vm = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = mockChatDialogueDao,
            perceptionPipeline = mockPerception
        )

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flowOf(
            LlmStreamEvent.Token("在看B站呀")
        )

        vm.sendMessage("你在干嘛")
        testScheduler.advanceUntilIdle()

        // 验证调用了混合检索 retrieve 而不是旁路的纯 2-gram
        coVerify {
            mockMemoryManager.retrieve(
                query = "你在干嘛",
                topK = 5,
                nowTs = any(),
                moodValence = any()
            )
        }

        // 验证 assembleMapBasedContext 接收到了 perceptionSnapshot
        coVerify {
            mockMemoryManager.assembleMapBasedContext(
                query = "你在干嘛",
                moodValence = any(),
                charName = any(),
                nowTs = any(),
                perceptionSnapshot = match { it?.foregroundApp?.packageName == "tv.danmaku.bili" },
                preRecalledUserMemories = any(),
                preRecalledSelfExperiences = any(),
                preRecalledUnspokenThoughts = any(),
                preRecalledWorldviews = any()
            )
        }
    }

    @Test
    fun testSendMessage_injectsEmbodiedNarrativeIntoDynamicContext() = runTest(testDispatcher) {
        val mockPerception = mockk<com.lirica.android.perception.PerceptionPipeline>()
        val mockSnapshot = com.lirica.android.perception.model.PerceptionSnapshot(
            foregroundApp = com.lirica.android.perception.model.ForegroundAppInfo(
                packageName = "com.netease.cloudmusic"
            ),
            hardwareEnvironment = com.lirica.android.perception.model.HardwareEnvironmentState(
                isHeadsetPlugged = true,
                isAudioPlaying = true
            )
        )
        every { mockPerception.snapshotFlow } returns kotlinx.coroutines.flow.MutableStateFlow(mockSnapshot)

        val vm = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = mockChatDialogueDao,
            perceptionPipeline = mockPerception
        )

        val messagesSlot = slot<List<com.lirica.android.network.model.ChatMessage>>()
        every { mockLlmClient.streamChat(any(), capture(messagesSlot), any()) } returns flowOf(
            LlmStreamEvent.Token("在听歌呢")
        )

        vm.sendMessage("哈喽")
        testScheduler.advanceUntilIdle()

        assertTrue(messagesSlot.isCaptured)
        val sentMessages = messagesSlot.captured
        val systemMessages = sentMessages.filter { it.role == "system" }
        assertTrue(systemMessages.isNotEmpty())
        val combinedSystemText = systemMessages.joinToString("\n") { it.content ?: "" }
        assertTrue("应包含具身耳机叙事", combinedSystemText.contains("戴着耳机听音乐"))
    }

    @Test
    fun testInterruptGeneration_cancelsStreamingAndAddsComplainingMessage() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flow {
            emit(LlmStreamEvent.Token("正在说很多很多话，但永远流不完..."))
            kotlinx.coroutines.delay(10000L) // 模拟卡住
            emit(LlmStreamEvent.Token("后续话语"))
        }

        viewModel.sendMessage("随便说点什么")
        testScheduler.advanceTimeBy(500L)

        assertTrue(viewModel.uiState.value.isStreaming)

        // 用户触发打断！
        viewModel.interruptGeneration("用户嫌卡住了")
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("打断后必须重置 isStreaming 为 false", state.isStreaming)
        assertTrue("打断后必须保留至少2条消息（用户+抱怨回复）", state.messages.size >= 2)
        val lastMsg = state.messages.last()
        assertEquals(MessageSender.ASSISTANT, lastMsg.sender)
        assertTrue(
            "打断回复必须包含抱怨或疑惑语气词",
            lastMsg.text.contains("欸") || lastMsg.text.contains("拉住") ||
                lastMsg.text.contains("按停") || lastMsg.text.contains("停下") ||
                lastMsg.text.contains("按住") || lastMsg.text.contains("收声")
        )
    }


    @Test
    fun testDialoguePersistence_savesAndRestoresMemoryBadgeMetadata() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()

        val insertedEntities = mutableListOf<ChatDialogueEntity>()
        coEvery { mockChatDialogueDao.insert(capture(insertedEntities)) } returns 1L

        val mockUserMemory = UserMemoryEntity(
            id = "mem_thesis",
            content = "用户周三要交开题报告",
            category = "计划",
            createdAt = 1696250000000L
        )
        coEvery { mockMemoryManager.retrieve(any(), any(), any(), any()) } returns listOf(mockUserMemory)

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flow {
            emit(LlmStreamEvent.Token("开题报告加油哦"))
            emit(LlmStreamEvent.Done("stop"))
        }

        viewModel.sendMessage("开题报告")
        testScheduler.advanceUntilIdle()

        val assistantEntity = insertedEntities.firstOrNull { it.role == ChatDialogueEntity.ROLE_ASSISTANT }
        assertNotNull("必须持久化助手回复", assistantEntity)
        assertNotNull("必须将记忆溯源保存至 metadataJson", assistantEntity!!.metadataJson)
        assertTrue("metadataJson 必须包含原记忆内容", assistantEntity.metadataJson!!.contains("用户周三要交开题报告"))

        // 测试重启后从 DAO 恢复 memoryBadge
        coEvery { mockChatDialogueDao.getRecentBySession(any(), any()) } returns listOf(assistantEntity)
        val restoredVm = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = mockChatDialogueDao
        )
        testScheduler.advanceUntilIdle()

        val restoredMsg = restoredVm.uiState.value.messages.firstOrNull()
        assertNotNull("重启后必须恢复历史消息", restoredMsg)
        assertNotNull("重启后必须成功还原 memoryBadge", restoredMsg!!.memoryBadge)
        assertEquals("必须还原记忆条数", 1, restoredMsg.memoryBadge!!.userMemories.size)
        assertEquals("用户周三要交开题报告", restoredMsg.memoryBadge!!.userMemories[0])
        assertEquals(1696250000000L, restoredMsg.memoryBadge!!.memoryDetails[0].createdAt)
    }

}
