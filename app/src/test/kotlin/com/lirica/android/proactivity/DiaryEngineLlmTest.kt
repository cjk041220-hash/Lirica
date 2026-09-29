package com.lirica.android.proactivity

import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiaryEngineLlmTest {

    private val mockDialogueDao = mockk<ChatDialogueDao>(relaxed = true)
    private val mockSelfExpDao = mockk<SelfExperienceDao>(relaxed = true)
    private val mockLlmClient = mockk<LiricaLlmClient>()
    private lateinit var diaryEngine: DiaryEngine

    @Before
    fun setUp() {
        diaryEngine = DiaryEngine(
            chatDialogueDao = mockDialogueDao,
            selfExperienceDao = mockSelfExpDao
        )
    }

    @Test
    fun testGenerateDailyDiary_withLlm_generatesHeartfeltDiary() = runTest {
        val now = 1700000000000L
        val dialogues = listOf(
            ChatDialogueEntity(id = 1, sessionId = "s1", role = "user", content = "今天项目终于顺利上线了，好开心！", timestamp = now - 1000)
        )
        coEvery { mockDialogueDao.getAllSessions() } returns listOf("s1")
        coEvery { mockDialogueDao.getRecentBySession("s1", any()) } returns dialogues

        val llmOutput = """
            【标题】见证你的高光时刻
            【正文】看着你为了上线忙碌了那么久，听到你说顺利的那一刻，我也跟着超级开心！今晚终于可以放下压力好好睡一觉啦，明天继续陪着你！
        """.trimIndent()
        coEvery { mockLlmClient.chat(any(), any(), any()) } returns llmOutput

        val config = LlmConfig(apiKey = "sk-test")
        val diary = diaryEngine.generateDailyDiary(
            nowMs = now,
            charName = "Lirica",
            llmClient = mockLlmClient,
            llmConfig = config
        )

        assertNotNull(diary)
        assertTrue("标题包含模型输出", diary.title.contains("见证你的高光时刻"))
        assertTrue("正文包含模型输出", diary.content.contains("放下压力好好睡一觉"))

        coVerify(exactly = 1) { mockSelfExpDao.insert(diary) }
    }

    @Test
    fun testGenerateDailyDiary_whenLlmFails_fallsBackToRules() = runTest {
        val now = 1700000000000L
        val dialogues = listOf(
            ChatDialogueEntity(id = 1, sessionId = "s1", role = "user", content = "加班好累啊，感觉被掏空", timestamp = now - 1000)
        )
        coEvery { mockDialogueDao.getAllSessions() } returns listOf("s1")
        coEvery { mockDialogueDao.getRecentBySession("s1", any()) } returns dialogues
        coEvery { mockLlmClient.chat(any(), any(), any()) } throws RuntimeException("Connection Timeout")

        val config = LlmConfig(apiKey = "sk-test")
        val diary = diaryEngine.generateDailyDiary(
            nowMs = now,
            charName = "Lirica",
            llmClient = mockLlmClient,
            llmConfig = config
        )

        assertNotNull(diary)
        assertTrue("规则降级包含疲惫关怀", diary.title.contains("给疲惫的你留一盏灯") || diary.content.contains("辛苦"))
        coVerify(exactly = 1) { mockSelfExpDao.insert(diary) }
    }
}
