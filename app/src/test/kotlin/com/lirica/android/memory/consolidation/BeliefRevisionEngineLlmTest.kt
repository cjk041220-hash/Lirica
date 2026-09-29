package com.lirica.android.memory.consolidation

import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BeliefRevisionEngineLlmTest {

    private val mockLlmClient = mockk<LiricaLlmClient>()
    private lateinit var engine: BeliefRevisionEngine

    @Before
    fun setUp() {
        engine = BeliefRevisionEngine()
    }

    @Test
    fun testDetectAndReviseWithLlm_detectsArbitraryContradiction() = runTest {
        val oldMem = UserMemoryEntity(
            id = "mem_old_coriander",
            content = "用户最讨厌吃香菜，吃火锅绝对不点香菜",
            status = UserMemoryEntity.STATUS_ACTIVE,
            createdAt = 1000L
        )
        val newMem = UserMemoryEntity(
            id = "mem_new_coriander",
            content = "用户最近开始喜欢吃香菜了，觉得香菜配牛肉很香",
            status = UserMemoryEntity.STATUS_ACTIVE,
            createdAt = 5000L
        )

        // 模拟 LLM 识别出超出硬编码规则的新矛盾
        val llmResponse = """[{"oldId": "mem_old_coriander", "newId": "mem_new_coriander", "reason": "对香菜的喜好发生完全逆转"}]"""
        coEvery { mockLlmClient.chat(any(), any(), any()) } returns llmResponse

        val config = LlmConfig(apiKey = "sk-test")
        val result = engine.detectAndReviseWithLlm(
            existingMemories = listOf(oldMem),
            newMemories = listOf(newMem),
            nowMs = 6000L,
            llmClient = mockLlmClient,
            llmConfig = config
        )

        assertEquals(1, result.supersededMemories.size)
        val superseded = result.supersededMemories.first()
        assertEquals("mem_old_coriander", superseded.id)
        assertEquals("mem_new_coriander", superseded.supersededBy)
        assertEquals(UserMemoryEntity.STATUS_SUPERSEDED, superseded.status)
        assertEquals(6000L, superseded.validTo)
    }

    @Test
    fun testDetectAndReviseWithLlm_whenLlmFails_fallsBackToRulesSafely() = runTest {
        val oldMem = UserMemoryEntity(
            id = "mem_old_coffee",
            content = "每天早上一杯美式黑咖啡",
            status = UserMemoryEntity.STATUS_ACTIVE,
            createdAt = 1000L
        )
        val newMem = UserMemoryEntity(
            id = "mem_new_tea",
            content = "最近胃不舒服，戒掉咖啡改喝大麦茶了",
            status = UserMemoryEntity.STATUS_ACTIVE,
            createdAt = 5000L
        )

        coEvery { mockLlmClient.chat(any(), any(), any()) } throws RuntimeException("Network Timeout")

        val config = LlmConfig(apiKey = "sk-test")
        val result = engine.detectAndReviseWithLlm(
            existingMemories = listOf(oldMem),
            newMemories = listOf(newMem),
            nowMs = 6000L,
            llmClient = mockLlmClient,
            llmConfig = config
        )

        // 降级至规则匹配，成功捕获咖啡冲突
        assertEquals(1, result.supersededMemories.size)
        assertEquals("mem_old_coffee", result.supersededMemories.first().id)
    }
}
