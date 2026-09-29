package com.lirica.android.persona

import com.lirica.android.network.model.ChatMessage
import com.lirica.android.persona.model.HandoffSnapshot
import com.lirica.android.ui.chat.ChatHistoryTrimmer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffManagerTest {

    private val handoffManager = HandoffManager()

    @Test
    fun testMaybeCreateSnapshot_emptyMessages_returnsNull() {
        val snapshot = handoffManager.maybeCreateSnapshot("s1", emptyList())
        assertNull(snapshot)
    }

    @Test
    fun testMaybeCreateSnapshot_extractsSummaryAndCommitments() {
        val messages = listOf(
            ChatMessage(role = "user", content = "我今天刚到深圳，准备参加明天的技术交流会。"),
            ChatMessage(role = "assistant", content = "深圳今天天气很不错呢，明天的交流会加油呀！"),
            ChatMessage(role = "user", content = "好，待会提醒我晚上去见大学室友。"),
            ChatMessage(role = "assistant", content = "没问题，我记在心上啦。")
        )

        val snapshot = handoffManager.maybeCreateSnapshot(
            sessionId = "s1",
            outgoingMessages = messages,
            currentMoodTone = "关切开心"
        )

        assertNotNull(snapshot)
        assertEquals("s1", snapshot!!.sessionId)
        assertEquals(4, snapshot.coveredTurns)
        assertEquals("关切开心", snapshot.emotionalTone)
        assertTrue(snapshot.sessionSummary.isNotEmpty())
        assertTrue(snapshot.unresolvedCommitments.isNotEmpty())

        val anchor = snapshot.toAnchorPrompt()
        assertTrue(anchor.contains("上一阶段对话交接快照"))
        assertTrue(anchor.contains("待续约定"))
        assertTrue(anchor.contains("关切开心"))
    }

    @Test
    fun testChatHistoryTrimmer_trimWithSnapshot_injectsAnchorAtHead() {
        val snapshot = HandoffSnapshot(
            sessionId = "s1",
            sessionSummary = "用户到达深圳准备技术会议",
            unresolvedCommitments = listOf("提醒晚上见室友"),
            emotionalTone = "愉快"
        )

        val history = listOf(
            ChatMessage(role = "user", content = "我刚刚办完酒店入住了。"),
            ChatMessage(role = "assistant", content = "那就好，稍微歇息一下喝口水吧！")
        )

        val trimmedWithSnapshot = ChatHistoryTrimmer.trimWithSnapshot(
            history = history,
            snapshot = snapshot,
            maxTokens = 4000
        )

        // 验证快照被锚定在头部
        assertEquals(3, trimmedWithSnapshot.size)
        val firstMessage = trimmedWithSnapshot.first()
        assertEquals("system", firstMessage.role)
        assertTrue(firstMessage.content!!.contains("上一阶段对话交接快照"))
        assertTrue(firstMessage.content!!.contains("用户到达深圳准备技术会议"))

        // 验证后文保持不变
        assertEquals("user", trimmedWithSnapshot[1].role)
        assertEquals("我刚刚办完酒店入住了。", trimmedWithSnapshot[1].content)
    }
}
