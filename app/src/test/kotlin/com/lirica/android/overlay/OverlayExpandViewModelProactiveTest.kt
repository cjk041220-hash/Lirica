package com.lirica.android.overlay

import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.overlay.viewmodel.OverlayExpandViewModel
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import com.lirica.android.proactivity.DecisionResult
import com.lirica.android.proactivity.ProactiveBubbleCoordinator
import com.lirica.android.proactivity.ProactiveCoordinationResult
import com.lirica.android.proactivity.ProactivityLevel
import com.lirica.android.proactivity.UserFeedback
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayExpandViewModelProactiveTest {

    private val testDispatcher = StandardTestDispatcher()

    private val unspokenThoughtDao: UnspokenThoughtDao = mockk(relaxed = true)
    private val emotionEngine: EmotionEngine = mockk(relaxed = true)
    private val llmClient: LiricaLlmClient = mockk(relaxed = true)
    private val memoryManager: DualTrackMemoryManager = mockk(relaxed = true)
    private val phiMiddleware: PhiMiddleware = mockk(relaxed = true)
    private val humannessFilter: HumannessFilter = mockk(relaxed = true)
    private val proactiveCoordinator: ProactiveBubbleCoordinator = mockk(relaxed = true)

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
            proactiveCoordinator = proactiveCoordinator
        )
    }

    @Test
    fun testProcessPerception_whenCoordinatorReturnsL2_updatesIcebreakerThought() = runTest {
        val thought = UnspokenThoughtEntity(
            id = "thought_urgent_1",
            thought = "主人，您看这篇新闻很有意思呢~",
            urgency = 0.9f,
            createdAt = 1000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )

        val snapshot = PerceptionSnapshot(
            foregroundApp = null,
            screenState = ScreenStateSnapshot(ScreenState.UNLOCKED, 1000L, 5000L),
            latestNotification = null,
            pageContent = null,
            isPrivacyFusing = false,
            perceivedSummary = "用户刚刚解锁手机"
        )

        coEvery { proactiveCoordinator.coordinate(snapshot, any()) } returns ProactiveCoordinationResult(
            level = ProactivityLevel.L2_ICEBREAKER_BUBBLE,
            decision = DecisionResult(
                action = com.lirica.android.proactivity.ProactiveAction.PROACTIVE_INTERRUPT,
                innerMotivation = 4.0,
                interruptCost = 1.2,
                effectiveMotivation = 4.8,
                breakpoint = com.lirica.android.proactivity.BreakpointResult(true, com.lirica.android.proactivity.BreakpointType.SCREEN_UNLOCK, 1.2),
                reason = "达到打断阈值且在任务断点"
            ),
            thought = thought,
            reason = "L2破冰气泡触发",
            bubblesShownToday = 1
        )

        val viewModel = createViewModel()
        viewModel.processPerception(snapshot, nowMs = 1000L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { proactiveCoordinator.coordinate(snapshot, 1000L) }
        val state = viewModel.uiState.value
        assertNotNull(state.icebreakerThought)
        assertEquals("thought_urgent_1", state.icebreakerThought?.id)
        assertEquals("主人，您看这篇新闻很有意思呢~", state.icebreakerThought?.thought)
    }

    @Test
    fun testProcessPerception_whenCoordinatorReturnsL0_doesNotShowBubble() = runTest {
        val snapshot = PerceptionSnapshot(
            foregroundApp = null,
            screenState = ScreenStateSnapshot(ScreenState.UNLOCKED, 1000L, 5000L),
            latestNotification = null,
            pageContent = null,
            isPrivacyFusing = false,
            perceivedSummary = "用户在使用手机"
        )

        coEvery { proactiveCoordinator.coordinate(snapshot, any()) } returns ProactiveCoordinationResult(
            level = ProactivityLevel.L0_SILENT,
            decision = DecisionResult(
                action = com.lirica.android.proactivity.ProactiveAction.SILENT,
                innerMotivation = 1.0,
                interruptCost = 2.0,
                effectiveMotivation = 1.0,
                breakpoint = com.lirica.android.proactivity.BreakpointResult(false, com.lirica.android.proactivity.BreakpointType.NONE, 1.0),
                reason = "动机低于阈值"
            ),
            thought = null,
            reason = "静默",
            bubblesShownToday = 0
        )

        val viewModel = createViewModel()
        viewModel.processPerception(snapshot, nowMs = 1000L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { proactiveCoordinator.coordinate(snapshot, 1000L) }
        assertNull(viewModel.uiState.value.icebreakerThought)
    }

    @Test
    fun testUserFeedback_onIcebreakerClicked_reportsClickedAndConsumesThought() = runTest {
        val thought = UnspokenThoughtEntity(
            id = "thought_click_1",
            thought = "天气变冷了，多穿件衣服呀",
            urgency = 0.8f,
            createdAt = 1000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )

        coJustRun { unspokenThoughtDao.markConsumed(thought.id, any()) }
        coJustRun { proactiveCoordinator.onUserFeedback(UserFeedback.CLICKED, thought.id, any()) }

        val viewModel = createViewModel()
        viewModel.onIcebreakerClicked(thought, nowMs = 2000L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { proactiveCoordinator.onUserFeedback(UserFeedback.CLICKED, "thought_click_1", 2000L) }
        coVerify { unspokenThoughtDao.markConsumed("thought_click_1", 2000L) }
        assertTrue(viewModel.uiState.value.isExpanded)
        assertEquals("天气变冷了，多穿件衣服呀", viewModel.uiState.value.quickInputText)
        assertNull(viewModel.uiState.value.icebreakerThought)
    }

    @Test
    fun testUserFeedback_dismissIcebreaker_reportsDismissedAndMarksDiscarded() = runTest {
        val thought = UnspokenThoughtEntity(
            id = "thought_dismiss_1",
            thought = "发呆中...",
            urgency = 0.6f,
            createdAt = 1000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )

        coJustRun { unspokenThoughtDao.markDiscarded(thought.id) }
        coJustRun { proactiveCoordinator.onUserFeedback(UserFeedback.DISMISSED, thought.id, any()) }

        val viewModel = createViewModel()
        viewModel.dismissIcebreaker(thought, nowMs = 3000L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { proactiveCoordinator.onUserFeedback(UserFeedback.DISMISSED, "thought_dismiss_1", 3000L) }
        coVerify { unspokenThoughtDao.markDiscarded("thought_dismiss_1") }
        assertNull(viewModel.uiState.value.icebreakerThought)
    }

    @Test
    fun testUserFeedback_onIcebreakerIgnored_reportsIgnored() = runTest {
        val thought = UnspokenThoughtEntity(
            id = "thought_ignored_1",
            thought = "超市有打折草莓哦",
            urgency = 0.5f,
            createdAt = 1000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )

        coJustRun { proactiveCoordinator.onUserFeedback(UserFeedback.IGNORED, thought.id, any()) }

        val viewModel = createViewModel()
        viewModel.onIcebreakerIgnored(thought, nowMs = 4000L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { proactiveCoordinator.onUserFeedback(UserFeedback.IGNORED, "thought_ignored_1", 4000L) }
    }
}
