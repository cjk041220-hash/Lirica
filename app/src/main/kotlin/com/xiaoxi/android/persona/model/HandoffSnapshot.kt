package com.xiaoxi.android.persona.model

import kotlinx.serialization.Serializable

/**
 * 上下文交接快照领域模型 (Handoff Snapshot)
 *
 * 在历史长程对话轮次超标（如 >20 轮）或 Token 预算被截断时，
 * 为即将出局的历史段提炼结构化摘要。
 */
@Serializable
data class HandoffSnapshot(
    val sessionId: String,
    val sessionSummary: String,
    val unresolvedCommitments: List<String> = emptyList(),
    val emotionalTone: String = "自然温和",
    val timestamp: Long = System.currentTimeMillis(),
    val coveredTurns: Int = 0
) {
    /**
     * 格式化为注入对话历史开头的提示词重锚
     */
    fun toAnchorPrompt(): String {
        val commitmentsPart = if (unresolvedCommitments.isNotEmpty()) {
            " | 待续约定: " + unresolvedCommitments.joinToString("、")
        } else ""
        return "[上一阶段对话交接快照: $sessionSummary$commitmentsPart | 情感基调: $emotionalTone]"
    }
}
