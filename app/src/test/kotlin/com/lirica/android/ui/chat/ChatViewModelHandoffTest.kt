package com.lirica.android.ui.chat

import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.persona.HandoffManager
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import com.lirica.android.persona.model.HandoffSnapshot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelHandoffTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockLlmClient = mockk<LiricaLlmClient>()
    private val mockChatDialogueDao = mockk<ChatDialogueDao>(relaxed = true)
    private val mockMemoryManager = mockk<DualTrackMemoryManager>(relaxed = true)
    private lateinit var handoffManager: HandoffManager
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        handoffManager = HandoffManager()

        coEvery { mockChatDialogueDao.getRecentBySession(any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.recallUserMemories(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.recallSelfExperiences(any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.recallUnspokenThoughts(any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.assembleDynamicContext(any(), any(), any(), any(), any(), any(), any()) } returns null
        coEvery { mockMemoryManager.assembleMapBasedContext(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null

        viewModel = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = EmotionEngine(baseline = MoodState.CALM.defaultVector),
            phiMiddleware = PhiMiddleware(),
            humannessFilter = HumannessFilter(),
            chatDialogueDao = mockChatDialogueDao,
            handoffManager = handoffManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testChatViewModel_wiresHandoffManagerSnapshotOnLongSession() = runTest(testDispatcher) {
        val capturedMessages = mutableListOf<List<com.lirica.android.network.model.ChatMessage>>()
        coEvery { mockLlmClient.streamChat(any(), capture(capturedMessages)) } returns flowOf(
            LlmStreamEvent.Token("回复内容")
        )

        // 预设一个已有的 Handoff 快照
        val snapshot = HandoffSnapshot(
            sessionId = viewModel.sessionId,
            sessionSummary = "上一阶段聊到了周末露营计划",
            unresolvedCommitments = listOf("带防潮垫"),
            emotionalTone = "期待"
        )
        handoffManager.setSnapshot(viewModel.sessionId, snapshot)

        viewModel.onInputTextChanged("我们几点出发？")
        viewModel.sendMessage()
        testScheduler.advanceUntilIdle()

        // 验证发送给 LLM 的 assembledMessages 包含 Handoff 快照
        val sentHistory = capturedMessages.lastOrNull()
        assertNotNull(sentHistory)
        assertTrue(sentHistory!!.any { it.content?.contains("上一阶段对话交接快照") == true })
        assertTrue(sentHistory.any { it.content?.contains("周末露营计划") == true })
    }
}
