package com.xiaoxi.android.overlay

import app.cash.turbine.test
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.overlay.viewmodel.OverlayExpandViewModel
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
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
    private val llmClient: XiaoxiLlmClient = mockk(relaxed = true)
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
            humannessFilter = humannessFilter
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
        coEvery { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1) } returns listOf(thought)

        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refreshIcebreaker(nowTs = 2000L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { unspokenThoughtDao.discardExpiredThoughts(2000L) }
        coVerify { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1) }

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

        coEvery { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1) } returns listOf(thought)
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

        coEvery { unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1) } returns listOf(thought)
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
}
