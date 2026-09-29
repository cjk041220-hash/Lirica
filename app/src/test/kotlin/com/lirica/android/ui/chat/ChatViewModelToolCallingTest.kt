package com.lirica.android.ui.chat

import com.lirica.android.automation.agent.NativeToolAgent
import com.lirica.android.automation.agent.ToolExecutionResult
import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.network.model.ToolCall
import com.lirica.android.network.model.ToolCallFunction
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import io.mockk.coEvery
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelToolCallingTest {

    private val testDispatcher = StandardTestDispatcher()

    private val llmClient: LiricaLlmClient = mockk(relaxed = true)
    private val memoryManager: DualTrackMemoryManager = mockk(relaxed = true)
    private val emotionEngine: EmotionEngine = mockk(relaxed = true)
    private val phiMiddleware: PhiMiddleware = mockk(relaxed = true)
    private val humannessFilter: HumannessFilter = mockk(relaxed = true)
    private val chatDialogueDao: ChatDialogueDao = mockk(relaxed = true)
    private val toolAgent: NativeToolAgent = mockk(relaxed = true)

    private val moodFlow = MutableStateFlow(MoodState.CALM)
    private val vectorFlow = MutableStateFlow(EmotionVector(0f, 0f))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { emotionEngine.currentMood } returns MoodState.CALM
        every { emotionEngine.moodFlow } returns moodFlow
        every { emotionEngine.currentVector } returns EmotionVector(0f, 0f)
        every { emotionEngine.stateFlow } returns vectorFlow

        coEvery { memoryManager.recallUserMemories(any(), any(), any()) } returns emptyList()
        coEvery { memoryManager.recallSelfExperiences(any(), any()) } returns emptyList()
        coEvery { memoryManager.recallUnspokenThoughts(any()) } returns emptyList()
        coEvery { memoryManager.assembleDynamicContext(any(), any(), any(), any(), any(), any()) } returns "测试上下文"

        every {
            phiMiddleware.assemble(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            listOf(
                ChatMessage(role = "system", content = "系统设定"),
                ChatMessage(role = "user", content = "帮我打开高德地图")
            )
        }

        coEvery {
            humannessFilter.filterWithCorrectionLoop(any(), any(), any(), any(), any())
        } answers {
            HumannessFilter.FilterResult(
                passed = true,
                originalText = firstArg(),
                filteredText = firstArg(),
                violations = emptyList(),
                isRewritten = false
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(
            llmClient = llmClient,
            memoryManager = memoryManager,
            emotionEngine = emotionEngine,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter,
            chatDialogueDao = chatDialogueDao,
            toolAgent = toolAgent
        )
    }

    @Test
    fun testToolCallingLoop_executesToolsAndContinuesConversation() = runTest {
        // 第 1 轮 LLM 输出：发出 open_app 工具调用
        val streamRound1 = flowOf(
            LlmStreamEvent.ToolCallChunk(
                index = 0,
                id = "call_open_amap_1",
                name = "open_app",
                argumentsDelta = "{\"app_name\":\"高德地图\"}"
            )
        )

        // 第 2 轮 LLM 输出：在工具执行完毕后，总结答复
        val streamRound2 = flowOf(
            LlmStreamEvent.Token("已经为你打开高德地图啦"),
            LlmStreamEvent.Token("，还要去哪儿吗？")
        )

        coEvery {
            llmClient.streamChat(any(), any(), any())
        } returnsMany listOf(streamRound1, streamRound2)

        coEvery {
            toolAgent.executeToolCalls(any())
        } returns listOf(
            ToolExecutionResult(
                toolCallId = "call_open_amap_1",
                isSuccess = true,
                output = "应用 [高德地图] 已成功打开并置于前台"
            )
        )

        val viewModel = createViewModel()
        viewModel.sendMessage("帮我打开高德地图")
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证工具分发执行
        coVerify(exactly = 1) {
            toolAgent.executeToolCalls(match { calls ->
                calls.size == 1 && calls[0].name == "open_app" && calls[0].arguments.contains("高德地图")
            })
        }

        // 验证 LLM 流式被调用了 2 轮 (1轮发出工具调用, 2轮拿到工具结果后总结)
        coVerify(exactly = 2) {
            llmClient.streamChat(any(), any(), any())
        }

        // 验证最终消息
        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        val lastMsg = state.messages.last()
        assertEquals(MessageSender.ASSISTANT, lastMsg.sender)
        assertTrue(lastMsg.text.contains("已经为你打开高德地图啦"))
    }

    @Test
    fun testToolCallingLoop_stopsAtMaxIterationsToPreventInfiniteRecursion() = runTest {
        // 模拟 LLM 发生死循环，连续不断输出工具调用
        val infiniteStream = flowOf(
            LlmStreamEvent.ToolCallChunk(
                index = 0,
                id = "call_infinite",
                name = "click_element",
                argumentsDelta = "{\"text\":\"重试\"}"
            )
        )

        coEvery {
            llmClient.streamChat(any(), any(), any())
        } returns infiniteStream

        coEvery {
            toolAgent.executeToolCalls(any())
        } returns listOf(
            ToolExecutionResult(
                toolCallId = "call_infinite",
                isSuccess = false,
                output = "点击失败: 超时"
            )
        )

        val viewModel = createViewModel()
        viewModel.sendMessage("不断重试")
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证被严格截断在 5 轮以内，杜绝递归爆栈
        coVerify(exactly = 5) {
            toolAgent.executeToolCalls(any())
        }
        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
    }

    @Test
    fun testToolCallingLoop_whenToolExecutionFails_passesErrorOutputToLlm() = runTest {
        val streamRound1 = flowOf(
            LlmStreamEvent.ToolCallChunk(
                index = 0,
                id = "call_err_1",
                name = "open_app",
                argumentsDelta = "{\"app_name\":\"不存在的App\"}"
            )
        )

        val streamRound2 = flowOf(
            LlmStreamEvent.Token("抱歉，我没在你的手机上找到这个应用哦。")
        )

        coEvery {
            llmClient.streamChat(any(), any(), any())
        } returnsMany listOf(streamRound1, streamRound2)

        coEvery {
            toolAgent.executeToolCalls(any())
        } returns listOf(
            ToolExecutionResult(
                toolCallId = "call_err_1",
                isSuccess = false,
                output = "打开应用失败: 找不到目标应用"
            )
        )

        val viewModel = createViewModel()
        viewModel.sendMessage("打开不存在的App")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            toolAgent.executeToolCalls(any())
        }
        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        val lastMsg = state.messages.last()
        assertTrue(lastMsg.text.contains("抱歉，我没在你的手机上找到这个应用哦"))
    }
}
