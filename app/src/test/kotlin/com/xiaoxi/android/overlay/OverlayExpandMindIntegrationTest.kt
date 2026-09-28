package com.xiaoxi.android.overlay

import com.xiaoxi.android.automation.agent.NativeToolAgent
import com.xiaoxi.android.automation.agent.ToolExecutionResult
import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.database.memory.MemoryExtractor
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.network.model.ToolCall
import com.xiaoxi.android.network.model.ToolCallFunction
import com.xiaoxi.android.network.model.ToolDefinition
import com.xiaoxi.android.network.model.ToolFunction
import com.xiaoxi.android.overlay.viewmodel.OverlayExpandViewModel
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayExpandMindIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private val unspokenThoughtDao: UnspokenThoughtDao = mockk(relaxed = true)
    private val emotionEngine: EmotionEngine = mockk(relaxed = true)
    private val llmClient: XiaoxiLlmClient = mockk(relaxed = true)
    private val memoryManager: DualTrackMemoryManager = mockk(relaxed = true)
    private val phiMiddleware: PhiMiddleware = mockk(relaxed = true)
    private val humannessFilter: HumannessFilter = mockk(relaxed = true)
    private val chatDialogueDao: ChatDialogueDao = mockk(relaxed = true)
    private val toolAgent: NativeToolAgent = mockk(relaxed = true)
    private val memoryExtractor: MemoryExtractor = mockk(relaxed = true)

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

    @Test
    fun testOverlayQuickMessage_persistsDialogueAndExtractsMemory() = runTest {
        val capturedEntities = mutableListOf<ChatDialogueEntity>()
        coEvery { chatDialogueDao.insert(capture(capturedEntities)) } returns 1L
        coEvery { chatDialogueDao.getRecentBySession(OverlayExpandViewModel.OVERLAY_SESSION_ID, any()) } returns emptyList()

        coEvery { memoryManager.assembleDynamicContext(any(), any(), any(), any(), any(), any(), any()) } returns null
        coEvery {
            phiMiddleware.assemble(
                characterCard = any(),
                dynamicContext = any(),
                historyMessages = any(),
                moodState = any(),
                sessionId = any(),
                charName = any(),
                turnCount = any(),
                includeToolPersonaContract = any()
            )
        } returns listOf(ChatMessage(role = "user", content = "我今天去看了周杰伦演唱会"))

        every {
            llmClient.streamChat(any(), any(), any())
        } returns flowOf(
            LlmStreamEvent.Token("哇，周董的演唱会超棒的！")
        )

        every { humannessFilter.filter(any(), any()) } returns HumannessFilter.FilterResult(
            passed = true,
            originalText = "哇，周董的演唱会超棒的！",
            filteredText = "哇，周董的演唱会超棒的！",
            violations = emptyList(),
            isRewritten = false
        )

        val viewModel = OverlayExpandViewModel(
            unspokenThoughtDao = unspokenThoughtDao,
            emotionEngine = emotionEngine,
            llmClient = llmClient,
            memoryManager = memoryManager,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = chatDialogueDao,
            toolAgent = toolAgent,
            memoryExtractor = memoryExtractor
        )

        viewModel.sendQuickMessage("我今天去看了周杰伦演唱会")
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证用户与助手消息双向落库持久化
        assertEquals(2, capturedEntities.size)
        assertEquals("user", capturedEntities[0].role)
        assertEquals("我今天去看了周杰伦演唱会", capturedEntities[0].content)
        assertEquals(OverlayExpandViewModel.OVERLAY_SESSION_ID, capturedEntities[0].sessionId)

        assertEquals("assistant", capturedEntities[1].role)
        assertEquals("哇，周董的演唱会超棒的！", capturedEntities[1].content)
        assertEquals(OverlayExpandViewModel.OVERLAY_SESSION_ID, capturedEntities[1].sessionId)

        // 验证长期记忆抽取被触发
        coVerify(exactly = 1) {
            memoryExtractor.maybeExtract("我今天去看了周杰伦演唱会", "哇，周董的演唱会超棒的！", any(), any())
        }
    }

    @Test
    fun testOverlayQuickMessage_executesToolCallLoop() = runTest {
        coEvery { chatDialogueDao.getRecentBySession(any(), any()) } returns emptyList()
        coEvery { memoryManager.assembleDynamicContext(any(), any(), any(), any(), any(), any(), any()) } returns null
        coEvery {
            phiMiddleware.assemble(
                characterCard = any(),
                dynamicContext = any(),
                historyMessages = any(),
                moodState = any(),
                sessionId = any(),
                charName = any(),
                turnCount = any(),
                includeToolPersonaContract = any()
            )
        } returns listOf(ChatMessage(role = "user", content = "北京今天天气怎么样"))

        val weatherTool = ToolDefinition(
            type = "function",
            function = ToolFunction(name = "weather", description = "查天气")
        )
        every { toolAgent.declaredTools } returns listOf(weatherTool)

        val toolCallChunk = LlmStreamEvent.ToolCallChunk(
            index = 0,
            id = "call_weather_1",
            name = "weather",
            argumentsDelta = "{\"city\":\"北京\"}"
        )

        // 第一轮返回工具调用，第二轮返回根据工具结果的回复
        val call1 = ToolCall(id = "call_weather_1", function = ToolCallFunction(name = "weather", arguments = "{\"city\":\"北京\"}"))
        coEvery {
            toolAgent.executeToolCalls(listOf(call1))
        } returns listOf(
            ToolExecutionResult(toolCallId = "call_weather_1", isSuccess = true, output = "北京今天晴朗，22度")
        )

        var round = 0
        every {
            llmClient.streamChat(any(), any(), any())
        } answers {
            round++
            if (round == 1) {
                flowOf(toolCallChunk)
            } else {
                flowOf(LlmStreamEvent.Token("北京今天是大晴天哦，气温22度，很舒服！"))
            }
        }

        every { humannessFilter.filter(any(), any()) } returns HumannessFilter.FilterResult(
            passed = true,
            originalText = "北京今天是大晴天哦，气温22度，很舒服！",
            filteredText = "北京今天是大晴天哦，气温22度，很舒服！",
            violations = emptyList(),
            isRewritten = false
        )

        val viewModel = OverlayExpandViewModel(
            unspokenThoughtDao = unspokenThoughtDao,
            emotionEngine = emotionEngine,
            llmClient = llmClient,
            memoryManager = memoryManager,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = chatDialogueDao,
            toolAgent = toolAgent,
            memoryExtractor = memoryExtractor
        )

        viewModel.sendQuickMessage("北京今天天气怎么样")
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证工具调用被执行
        coVerify(exactly = 1) {
            toolAgent.executeToolCalls(listOf(call1))
        }

        val state = viewModel.uiState.value
        assertTrue(state.streamingReply.contains("晴天"))
    }
}
