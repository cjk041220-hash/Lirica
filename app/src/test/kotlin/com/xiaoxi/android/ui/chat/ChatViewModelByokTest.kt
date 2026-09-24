package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.common.settings.ByokSettings
import com.xiaoxi.android.common.settings.ByokSettingsRepository
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelByokTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockLlmClient = mockk<XiaoxiLlmClient>()
    private val mockMemoryManager = mockk<DualTrackMemoryManager>(relaxed = true)
    private val mockByokRepository = mockk<ByokSettingsRepository>()
    private val byokSettingsFlow = MutableStateFlow(ByokSettings())

    private lateinit var emotionEngine: EmotionEngine
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        emotionEngine = EmotionEngine()

        every { mockByokRepository.settings } returns byokSettingsFlow
        coEvery { mockMemoryManager.recallUserMemories(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMemoryManager.assembleDynamicContext(any(), any(), any(), any()) } returns null
        coEvery { mockMemoryManager.assembleMapBasedContext(any(), any(), any(), any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSendMessage_whenKeyMissing_setsErrorMessageAndDoesNotCallLlm() = runTest(testDispatcher) {
        every { mockByokRepository.getEffectiveLlmConfig() } returns LlmConfig(apiKey = "")

        viewModel = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            byokRepository = mockByokRepository
        )
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("你好")
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue("无 Key 时给出友好中文提示", state.errorMessage!!.contains("API Key"))
        assertTrue("显示配 Key 引导状态", state.isKeyPromptBannerVisible)
        assertFalse("流式状态重置为 false", state.isStreaming)

        coVerify(exactly = 0) { mockLlmClient.streamChat(any(), any(), any()) }
    }

    @Test
    fun testSendMessage_whenCustomKeyConfigured_passesCustomConfigToLlmClient() = runTest(testDispatcher) {
        val customConfig = LlmConfig(
            apiKey = "sk-custom-user-key-777",
            baseUrl = "https://api.custom.com/v1",
            model = "custom-deepseek"
        )
        every { mockByokRepository.getEffectiveLlmConfig() } returns customConfig
        coEvery { mockLlmClient.streamChat(any(), any(), any()) } returns flowOf(
            LlmStreamEvent.Token("你好主人"),
            LlmStreamEvent.Done("stop")
        )

        viewModel = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            byokRepository = mockByokRepository
        )
        testScheduler.advanceUntilIdle()

        viewModel.sendMessage("开机测试")
        testScheduler.advanceUntilIdle()

        val capturedConfig = slot<LlmConfig>()
        coVerify(exactly = 1) {
            mockLlmClient.streamChat(capture(capturedConfig), any(), any())
        }

        assertEquals("sk-custom-user-key-777", capturedConfig.captured.apiKey)
        assertEquals("https://api.custom.com/v1", capturedConfig.captured.baseUrl)
        assertEquals("custom-deepseek", capturedConfig.captured.model)
    }

    @Test
    fun testByokSettingsChange_updatesUiStateApiKeyConfigured() = runTest(testDispatcher) {
        every { mockByokRepository.getEffectiveLlmConfig() } returns LlmConfig(apiKey = "")

        viewModel = ChatViewModel(
            llmClient = mockLlmClient,
            memoryManager = mockMemoryManager,
            emotionEngine = emotionEngine,
            byokRepository = mockByokRepository
        )
        testScheduler.advanceUntilIdle()

        byokSettingsFlow.value = ByokSettings(customApiKey = "sk-valid-key")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isApiKeyConfigured)
    }
}
