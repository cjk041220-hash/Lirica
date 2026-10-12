package com.lirica.android.overlay

import app.cash.turbine.test
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.automation.coordinator.TaskExecutionState
import com.lirica.android.automation.feedback.ActionVisualFeedback
import com.lirica.android.automation.feedback.EmbodiedState
import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.overlay.viewmodel.OverlayExpandViewModel
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class OverlayExpandViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val unspokenThoughtDao: UnspokenThoughtDao = mockk(relaxed = true)
    private val emotionEngine: EmotionEngine = mockk(relaxed = true)
    private val llmClient: LiricaLlmClient = mockk(relaxed = true)
    private val memoryManager: DualTrackMemoryManager = mockk(relaxed = true)
    private val phiMiddleware: PhiMiddleware = mockk(relaxed = true)
    private val humannessFilter: HumannessFilter = mockk(relaxed = true)

    private val moodFlow = MutableStateFlow(MoodState.CALM)
    private val vectorFlow = MutableStateFlow(EmotionVector(0f, 0f))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { emotionEngine.currentMood } returns MoodState.CALM
        every { emotionEngine.moodFlow } returns moodFlow
        every { emotionEngine.currentVector } returns EmotionVector(0f, 0f)
        every { emotionEngine.stateFlow } returns vectorFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): OverlayExpandViewModel {
        return OverlayExpandViewModel(
            unspokenThoughtDao = unspokenThoughtDao,
            emotionEngine = emotionEngine,
            llmClient = llmClient,
            memoryManager = memoryManager,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            autoDismissTimeoutMs = 0L
        )
    }

    @Test
    fun testRefreshIcebreaker_filtersExpiredAndPicksTopUrgent() = runTest {
        val thought = UnspokenThoughtEntity(
            id = "thought_1",
            thought = "主人好像在专注敲代码呢...",
            urgency = 0.85f,
            decayScore = 1.0f,
            createdAt = 1000L,
            expiryTime = 5000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )

        coEvery { unspokenThoughtDao.discardExpiredThoughts(any()) } returns 2
        coEvery { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1, nowMs = any()) } returns listOf(thought)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refreshIcebreaker(nowTs = 2000L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { unspokenThoughtDao.discardExpiredThoughts(2000L) }
        coVerify { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1, nowMs = any()) }

        val state = viewModel.uiState.value
        assertNotNull(state.icebreakerThought)
        assertEquals("thought_1", state.icebreakerThought?.id)
        assertEquals("主人好像在专注敲代码呢...", state.icebreakerThought?.thought)
    }

    @Test
    fun testDismissIcebreaker() = runTest {
        val thought = UnspokenThoughtEntity(
            id = "thought_1",
            thought = "想和Lirica说说话吗？",
            urgency = 0.7f,
            createdAt = 1000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )

        coEvery { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1, nowMs = any()) } returns listOf(thought)
        coJustRun { unspokenThoughtDao.markDiscarded(thought.id) }

        val viewModel = createViewModel()
        viewModel.refreshIcebreaker(nowTs = 1000L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("thought_1", viewModel.uiState.value.icebreakerThought?.id)

        viewModel.dismissIcebreaker(thought)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { unspokenThoughtDao.markDiscarded(thought.id) }
        assertNull(viewModel.uiState.value.icebreakerThought)
    }

    @Test
    fun testOnIcebreakerClicked() = runTest {
        val thought = UnspokenThoughtEntity(
            id = "thought_2",
            thought = "今天天气很不错哦~",
            urgency = 0.9f,
            createdAt = 1000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )

        coEvery { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1, nowMs = any()) } returns listOf(thought)
        coJustRun { unspokenThoughtDao.markConsumed(thought.id, any()) }

        val viewModel = createViewModel()
        viewModel.refreshIcebreaker(nowTs = 1000L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onIcebreakerClicked(thought)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { unspokenThoughtDao.markConsumed(thought.id, any()) }
        val state = viewModel.uiState.value
        assertTrue(state.isExpanded)
        assertNull(state.icebreakerThought)
        assertEquals("今天天气很不错哦~", state.quickInputText)
    }

    @Test
    fun testToggleExpanded() = runTest {
        val viewModel = createViewModel()
        assertFalse(viewModel.uiState.value.isExpanded)

        viewModel.toggleExpanded()
        assertTrue(viewModel.uiState.value.isExpanded)

        viewModel.toggleExpanded()
        assertFalse(viewModel.uiState.value.isExpanded)
    }

    @Test
    fun testSendQuickMessageStreamingAccumulation() = runTest {
        coEvery { memoryManager.assembleDynamicContext(any(), any(), any(), any()) } returns "测试上下文"
        every { phiMiddleware.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { llmClient.streamChat(any(), any(), any()) } returns flowOf(
            LlmStreamEvent.Token("Lirica"),
            LlmStreamEvent.Token("收到啦"),
            LlmStreamEvent.Token("！")
        )
        every { humannessFilter.filter("Lirica收到啦！", any()) } returns HumannessFilter.FilterResult(
            passed = true,
            originalText = "Lirica收到啦！",
            filteredText = "Lirica收到啦！",
            violations = emptyList(),
            isRewritten = false
        )

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.isStreaming)

            viewModel.sendQuickMessage("你好")

            // Streaming start
            val streamingStart = awaitItem()
            assertTrue(streamingStart.isStreaming)
            assertEquals("", streamingStart.streamingReply)

            // Delta 1
            val delta1 = awaitItem()
            assertEquals("Lirica", delta1.streamingReply)

            // Delta 2
            val delta2 = awaitItem()
            assertEquals("Lirica收到啦", delta2.streamingReply)

            // Delta 3
            val delta3 = awaitItem()
            assertEquals("Lirica收到啦！", delta3.streamingReply)

            // Finish
            val finished = awaitItem()
            assertFalse(finished.isStreaming)
            assertEquals("Lirica收到啦！", finished.streamingReply)
        }
    }

    @Test
    fun testOnTaskExecutionStateChanged_drivesCapsuleAndSpeech() = runTest {
        val viewModel = createViewModel()

        // 1. Planning
        viewModel.onTaskExecutionStateChanged(TaskExecutionState.Planning("打开设置查看存储"))
        var state = viewModel.uiState.value
        assertTrue(state.isActionRunning)
        assertEquals(EmbodiedState.THINKING, state.embodiedState)
        assertTrue(state.actionStatusText?.contains("正在规划") == true)

        // 2. Executing step
        viewModel.onTaskExecutionStateChanged(
            TaskExecutionState.ExecutingStep(1, 5, "点击存储选项", isRecovering = false)
        )
        state = viewModel.uiState.value
        assertTrue(state.isActionRunning)
        assertFalse(state.isActionRecovering)
        assertEquals(EmbodiedState.ACTING, state.embodiedState)
        assertTrue(state.actionStatusText?.contains("第 1 步") == true)

        // 3. Recovering step (卡住自愈)
        viewModel.onTaskExecutionStateChanged(
            TaskExecutionState.ExecutingStep(2, 5, "关闭拦截弹窗", isRecovering = true)
        )
        state = viewModel.uiState.value
        assertTrue(state.isActionRunning)
        assertTrue(state.isActionRecovering)
        assertEquals(EmbodiedState.OBSERVING, state.embodiedState)
        assertTrue(state.actionStatusText?.contains("自愈重试") == true)

        // 4. Completed -> 自动弹出话语
        viewModel.onTaskExecutionStateChanged(
            TaskExecutionState.Completed("已找到存储空间剩余 128GB", 3)
        )
        state = viewModel.uiState.value
        assertFalse(state.isActionRunning)
        assertTrue(state.isActionCompletedOrFailed)
        assertEquals(EmbodiedState.SUCCESS, state.embodiedState)
        assertEquals("任务完成", state.actionStatusText)
        assertTrue(state.actionSpeechText?.contains("已找到存储空间剩余 128GB") == true)

        // 手动关闭话语弹泡
        viewModel.dismissActionSpeech()
        state = viewModel.uiState.value
        assertNull(state.actionSpeechText)
        assertFalse(state.isActionCompletedOrFailed)
    }

    @Test
    fun testOnActionFeedback_bubbleStateAndProgress() = runTest {
        val viewModel = createViewModel()

        viewModel.onActionFeedback(
            ActionVisualFeedback.BubbleState(
                state = EmbodiedState.OBSERVING,
                speech = "我先看看屏幕上有什么"
            )
        )
        var state = viewModel.uiState.value
        assertEquals(EmbodiedState.OBSERVING, state.embodiedState)
        assertEquals("我先看看屏幕上有什么", state.actionSpeechText)

        viewModel.onActionFeedback(
            ActionVisualFeedback.StepProgress(
                stepIndex = 3,
                totalSteps = 6,
                description = "点击搜索按钮",
                isRecovering = false
            )
        )
        state = viewModel.uiState.value
        assertTrue(state.isActionRunning)
        assertEquals(EmbodiedState.ACTING, state.embodiedState)
        assertEquals("第 3/6 步: 点击搜索按钮", state.actionStatusText)
    }

    @Test
    fun testRecentMessages_collectedFromDaoFlow() = runTest {
        val chatDao: ChatDialogueDao = mockk(relaxed = true)
        val entity1 = ChatDialogueEntity(
            id = 1L,
            sessionId = OverlayExpandViewModel.OVERLAY_SESSION_ID,
            role = ChatDialogueEntity.ROLE_USER,
            content = "帮我查下明天的天气",
            timestamp = 1000L
        )
        val entity2 = ChatDialogueEntity(
            id = 2L,
            sessionId = OverlayExpandViewModel.OVERLAY_SESSION_ID,
            role = ChatDialogueEntity.ROLE_ASSISTANT,
            content = "明天晴天，24度哦~",
            timestamp = 1001L
        )

        every {
            chatDao.getRecentBySessionFlow(OverlayExpandViewModel.OVERLAY_SESSION_ID, limit = 3)
        } returns flowOf(listOf(entity1, entity2))

        val viewModel = OverlayExpandViewModel(
            unspokenThoughtDao = unspokenThoughtDao,
            emotionEngine = emotionEngine,
            llmClient = llmClient,
            memoryManager = memoryManager,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = chatDao,
            autoDismissTimeoutMs = 0L
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.recentMessages.size)
        assertEquals("帮我查下明天的天气", state.recentMessages[0].content)
        assertEquals("明天晴天，24度哦~", state.recentMessages[1].content)
    }

    @Test
    fun testInterruptCurrentAction_triggersCoordinatorAndShowsComplaintSpeech() = runTest {
        val viewModel = createViewModel()
        var coordinatorCancelled = false
        viewModel.interruptCurrentAction { coordinatorCancelled = true }

        assertTrue("必须调用底层协调器的取消回调", coordinatorCancelled)
        val state = viewModel.uiState.value
        assertFalse(state.isActionRunning)
        assertEquals(com.lirica.android.automation.feedback.EmbodiedState.USER_INTERRUPTED, state.embodiedState)
        assertNotNull(state.actionSpeechText)
        assertTrue(
            "代行打断必须弹出年上抱怨或疑惑文案",
            state.actionSpeechText!!.contains("拉住") || state.actionSpeechText!!.contains("按停") ||
                state.actionSpeechText!!.contains("按住") || state.actionSpeechText!!.contains("欸")
        )
    }

}
