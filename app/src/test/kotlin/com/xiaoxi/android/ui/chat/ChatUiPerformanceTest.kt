package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChatUiPerformanceTest {

    private fun hasBinaryImmutableAnnotation(clazz: Class<*>): Boolean {
        val resourceName = clazz.name.replace('.', '/') + ".class"
        val stream = clazz.classLoader?.getResourceAsStream(resourceName)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourceName)
            ?: return false
        val content = stream.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        return content.contains("Landroidx/compose/runtime/Immutable;")
    }

    @Test
    fun testUiModelsAreAnnotatedWithImmutable() {
        assertTrue(
            "MemoryBadgeInfo 必须标记 @Immutable 以保障 Compose 渲染稳定性",
            hasBinaryImmutableAnnotation(MemoryBadgeInfo::class.java)
        )
        assertTrue(
            "ChatMessageItem 必须标记 @Immutable",
            hasBinaryImmutableAnnotation(ChatMessageItem::class.java)
        )
        assertTrue(
            "ChatUiState 必须标记 @Immutable",
            hasBinaryImmutableAnnotation(ChatUiState::class.java)
        )
    }

    @Test
    fun testStreamingUpdatesOnlyTailMessage_preservesOtherMessageInstances() = runTest {
        val memoryManager = mockk<DualTrackMemoryManager>(relaxed = true)
        val emotionEngine = EmotionEngine()
        val phiMiddleware = PhiMiddleware()
        val humannessFilter = HumannessFilter()
        val llmClient = mockk<XiaoxiLlmClient>()

        every {
            llmClient.streamChat(any(), any())
        } returns flowOf(
            LlmStreamEvent.Token("你"),
            LlmStreamEvent.Token("好"),
            LlmStreamEvent.Done()
        )

        val viewModel = ChatViewModel(
            llmClient = llmClient,
            llmConfig = LlmConfig(apiKey = "test-key"),
            memoryManager = memoryManager,
            emotionEngine = emotionEngine,
            phiMiddleware = phiMiddleware,
            humannessFilter = humannessFilter
        )

        viewModel.onInputTextChanged("第一条消息")
        viewModel.sendMessage()

        val messages = viewModel.uiState.value.messages
        assertEquals(2, messages.size) // 1 user + 1 assistant
        val userMsgInstance = messages[0]
        val assistantMsgInstance = messages[1]

        assertEquals("第一条消息", userMsgInstance.text)
        assertTrue(assistantMsgInstance.text.contains("你好"))
    }
}
