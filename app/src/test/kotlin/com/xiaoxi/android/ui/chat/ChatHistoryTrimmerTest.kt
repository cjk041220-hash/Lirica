package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.network.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTrimmerTest {

    @Test
    fun testEstimateTokens_calculatesCorrectly() {
        assertEquals(0, ChatHistoryTrimmer.estimateTokens(""))
        // 15 个中文字符: 15 / 1.5 = 10 tokens
        assertEquals(10, ChatHistoryTrimmer.estimateTokens("一二三四五六七八九十一二三四五"))
        // 极短字符至少 1 token
        assertEquals(1, ChatHistoryTrimmer.estimateTokens("a"))
    }

    @Test
    fun testTrim_emptyHistory_returnsEmpty() {
        val result = ChatHistoryTrimmer.trim(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun testTrim_withinBudget_preservesAllMessages() {
        val history = listOf(
            ChatMessage(role = "user", content = "你好，小汐"),
            ChatMessage(role = "assistant", content = "你好！今天过得怎么样？"),
            ChatMessage(role = "user", content = "我今天刚下班，有点累。")
        )
        val result = ChatHistoryTrimmer.trim(history, maxTokens = 1000)
        assertEquals(3, result.size)
        assertEquals(history, result)
    }

    @Test
    fun testTrim_exceedsBudget_dropsOldestTurns() {
        // 每条约 60 字符 (40 tokens)
        val history = mutableListOf<ChatMessage>()
        for (i in 1..20) {
            history.add(ChatMessage(role = "user", content = "用户第 $i 次提问，详细描述当前任务以及各种要求细节 $i"))
            history.add(ChatMessage(role = "assistant", content = "助手第 $i 次回答，给出相应的解答方案与指引 $i"))
        }
        // 设定 maxTokens 为 120，大约只够保留最近 1~2 轮 (约 2~3 条)
        val result = ChatHistoryTrimmer.trim(history, maxTokens = 120)

        assertTrue(result.size < history.size)
        // 必须保留的是最新的一条
        assertEquals(history.last().content, result.last().content)
        // 且首条必须是对齐的 user 消息
        assertEquals("user", result.first().role)
    }

    @Test
    fun testTrim_alignsToUserMessageStart_whenOldestRemainingIsAssistant() {
        val history = listOf(
            ChatMessage(role = "user", content = "最老的提问"),
            ChatMessage(role = "assistant", content = "最老的回答"),
            ChatMessage(role = "user", content = "中间的提问"),
            ChatMessage(role = "assistant", content = "中间的回答"),
            ChatMessage(role = "user", content = "最新的提问")
        )
        // 限制 token 恰好装下 "中间的回答" 与 "最新的提问"
        // 此时预算截断若切在 "中间的回答"，算法应自动丢弃该 assistant 消息，保证输出从 user 开始
        val tokensLatest = ChatHistoryTrimmer.estimateTokens("最新的提问")
        val tokensMidAnswer = ChatHistoryTrimmer.estimateTokens("中间的回答")
        val budget = tokensLatest + tokensMidAnswer + 1

        val result = ChatHistoryTrimmer.trim(history, maxTokens = budget)
        assertEquals("user", result.first().role)
        assertEquals("最新的提问", result.first().content)
    }

    @Test
    fun testTrim_singleExtremelyLongMessage_truncatedSafely() {
        val hugeText = "超长文本片段".repeat(1000) // 6000 字符，约 4000 tokens
        val history = listOf(
            ChatMessage(role = "user", content = hugeText)
        )
        val result = ChatHistoryTrimmer.trim(history, maxTokens = 1000)
        assertEquals(1, result.size)
        assertTrue(result.first().content!!.contains("[...已省略超长历史内容...]"))
        assertTrue(ChatHistoryTrimmer.estimateTokens(result.first().content) <= 1000)
    }
}
