package com.lirica.android.persona

import com.lirica.android.network.model.ChatMessage
import com.lirica.android.persona.model.HandoffSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话交接快照管理器 (Handoff Manager)
 *
 * 遵循 Cortico 架构哲学：在对话轮次超标或硬预算截断时，
 * 为出局的历史轮次生成阶段性交接快照，保留话题脉络与情感锚点。
 */
class HandoffManager(
    val maxTurnsBeforeHandoff: Int = 20
) {

    private val snapshotCache = ConcurrentHashMap<String, HandoffSnapshot>()

    fun getLatestSnapshot(sessionId: String): HandoffSnapshot? = snapshotCache[sessionId]

    fun setSnapshot(sessionId: String, snapshot: HandoffSnapshot) {
        snapshotCache[sessionId] = snapshot
    }

    fun clearSnapshot(sessionId: String) {
        snapshotCache.remove(sessionId)
    }

    fun maybeCreateSnapshot(
        sessionId: String,
        outgoingMessages: List<ChatMessage>,
        currentMoodTone: String = "自然温和",
        nowMs: Long = System.currentTimeMillis()
    ): HandoffSnapshot? {
        if (outgoingMessages.isEmpty()) return null

        val userTexts = outgoingMessages
            .filter { it.role == "user" }
            .mapNotNull { it.content?.trim() }
            .filter { it.isNotEmpty() }

        if (userTexts.isEmpty()) return null

        // 提炼关键话题摘要
        val summaryTopics = userTexts.takeLast(5).joinToString("；") { text ->
            text.take(30)
        }

        // 提炼待续约定（含"待会"、"提醒"、"约定"、"计划"、"下次"、"明天"等）
        val commitments = mutableListOf<String>()
        val commitmentKeywords = listOf("待会", "晚点", "下次", "约定", "提醒", "明天", "下周", "后天", "准备")

        for (msg in outgoingMessages) {
            val content = msg.content.orEmpty()
            if (commitmentKeywords.any { content.contains(it) }) {
                val snippet = content.take(40).trim()
                if (snippet.isNotEmpty() && !commitments.contains(snippet)) {
                    commitments.add(snippet)
                }
            }
        }

        val snapshot = HandoffSnapshot(
            sessionId = sessionId,
            sessionSummary = summaryTopics,
            unresolvedCommitments = commitments.take(3),
            emotionalTone = currentMoodTone,
            timestamp = nowMs,
            coveredTurns = outgoingMessages.size
        )

        snapshotCache[sessionId] = snapshot
        return snapshot
    }
}
