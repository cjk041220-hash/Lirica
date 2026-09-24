package com.xiaoxi.android.memory.consolidation

import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.entity.WorldviewEntity
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DreamEmergenceSynthesizerLlmTest {

    private val mockLlmClient = mockk<XiaoxiLlmClient>()
    private lateinit var synthesizer: DreamEmergenceSynthesizer

    @Before
    fun setUp() {
        synthesizer = DreamEmergenceSynthesizer()
    }

    @Test
    fun testSynthesizeWithLlm_generatesOrganicThought() = runTest {
        val worldviews = listOf(
            WorldviewEntity(id = "wv_1", category = "学习习惯", statement = "用户习惯在夜间听轻音乐写代码", lastConfirmedAt = 1000L)
        )
        val superseded = listOf(
            UserMemoryEntity(id = "mem_old", content = "从前习惯早睡", prevContent = "早睡早起", status = UserMemoryEntity.STATUS_SUPERSEDED, createdAt = 500L)
        )

        val llmThought = "昨晚入梦整理时，看到你最近总在深夜伴着音乐专注写代码。虽然很厉害，但也要注意眼睛休息呀，早安！"
        coEvery { mockLlmClient.chat(any(), any(), any()) } returns llmThought

        val config = LlmConfig(apiKey = "sk-test")
        val thought = synthesizer.synthesizeWithLlm(
            worldviews = worldviews,
            supersededMemories = superseded,
            nowMs = 2000L,
            charName = "Lirica",
            llmClient = mockLlmClient,
            llmConfig = config
        )

        assertNotNull(thought)
        assertEquals(llmThought, thought.thought)
        assertEquals("morning_icebreaker", thought.targetScenario)
        assertEquals("subconscious_dream_consolidation", thought.triggerContext)
    }

    @Test
    fun testSynthesizeWithLlm_whenLlmFails_fallsBackToTemplate() = runTest {
        val worldviews = emptyList<WorldviewEntity>()
        val superseded = listOf(
            UserMemoryEntity(id = "mem_coffee", content = "改喝大麦茶", status = UserMemoryEntity.STATUS_SUPERSEDED, createdAt = 500L)
        )

        coEvery { mockLlmClient.chat(any(), any(), any()) } throws RuntimeException("Network Error")

        val config = LlmConfig(apiKey = "sk-test")
        val thought = synthesizer.synthesizeWithLlm(
            worldviews = worldviews,
            supersededMemories = superseded,
            nowMs = 2000L,
            charName = "Lirica",
            llmClient = mockLlmClient,
            llmConfig = config
        )

        assertNotNull(thought)
        assertTrue("规则兜底包含咖啡关心", thought.thought.contains("大麦茶") || thought.thought.contains("生活变化"))
    }
}
