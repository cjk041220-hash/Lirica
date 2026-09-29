package com.lirica.android.database.memory

import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmStreamEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MemoryExtractorThrottlingTest {

    private val llmClient = mockk<LiricaLlmClient>()
    private val userMemoryDao = mockk<UserMemoryDao>(relaxed = true)
    private val config = LlmConfig(apiKey = "test")
    private lateinit var extractor: MemoryExtractor

    @Before
    fun setup() {
        extractor = MemoryExtractor(
            llmClient = llmClient,
            userMemoryDao = userMemoryDao,
            llmConfig = config,
            throttleIntervalMs = 180_000L, // 3分钟
            minTurnsAccumulated = 3
        )
    }

    @Test
    fun testThrottling_firstTurnAccumulates_doesNotCallLlmUntilConditionMet() = runBlocking {
        // 第 1 轮：文本足够长，但 turnCount=1 且未超 3 分钟，不触发 LLM
        val res1 = extractor.maybeExtract(
            userText = "我非常喜欢看科幻小说尤其是大刘的作品",
            nowMs = 1000L
        )
        assertEquals(0, res1)
        assertEquals(1, extractor.getPendingTurnCount())
        coVerify(exactly = 0) { llmClient.streamChat(any(), any()) }

        // 第 2 轮：turnCount=2，仍未超 3 分钟，继续积累
        val res2 = extractor.maybeExtract(
            userText = "我也喜欢在周末去图书馆借书",
            nowMs = 2000L
        )
        assertEquals(0, res2)
        assertEquals(2, extractor.getPendingTurnCount())
        coVerify(exactly = 0) { llmClient.streamChat(any(), any()) }

        // 第 3 轮：turnCount=3 达到 minTurnsAccumulated，触发 LLM 抽取
        coEvery { llmClient.streamChat(any(), any()) } returns flowOf(
            LlmStreamEvent.Token("""{"memories":[{"content":"用户喜欢看科幻小说和大刘作品","category":"偏好","importance":6}]}""")
        )

        val res3 = extractor.maybeExtract(
            userText = "最近在重温三体",
            nowMs = 3000L
        )
        assertEquals(1, res3)
        assertEquals(0, extractor.getPendingTurnCount()) // 水位线已重置
        assertEquals(3000L, extractor.getLastExtractionTime())
        coVerify(exactly = 1) { llmClient.streamChat(any(), any()) }
    }

    @Test
    fun testThrottling_timeThresholdExceeded_triggersExtractionEvenIfFewerTurns() = runBlocking {
        extractor.maybeExtract("我下个月打算考驾照", nowMs = 1000L)
        assertEquals(1, extractor.getPendingTurnCount())

        // 经过 3.5 分钟（210秒），即使只有第 2 轮也应触发抽取
        coEvery { llmClient.streamChat(any(), any()) } returns flowOf(
            LlmStreamEvent.Token("""{"memories":[{"content":"用户计划下月考驾照","category":"计划","importance":7}]}""")
        )

        val res = extractor.maybeExtract("教练说下周开始科目二", nowMs = 1000L + 210_000L)
        assertEquals(1, res)
        assertEquals(0, extractor.getPendingTurnCount())
    }

    @Test
    fun testThrottling_forceFlag_bypassesThrottling() = runBlocking {
        coEvery { llmClient.streamChat(any(), any()) } returns flowOf(
            LlmStreamEvent.Token("""{"memories":[{"content":"用户有只猫叫雪球","category":"身份","importance":8}]}""")
        )

        val res = extractor.maybeExtract(
            userText = "我家的小猫叫雪球",
            nowMs = 1000L,
            force = true
        )
        assertEquals(1, res)
        coVerify(exactly = 1) { llmClient.streamChat(any(), any()) }
    }
}
