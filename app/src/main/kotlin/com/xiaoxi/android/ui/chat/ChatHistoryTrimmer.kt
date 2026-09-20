package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.network.model.ChatMessage

/**
 * 对话历史有界硬预算截断器
 *
 * 杜绝长程会话消息体无界膨胀触发大模型 400 报错或账单失控。
 */
object ChatHistoryTrimmer {

    /** 默认历史硬预算上限（Tokens） */
    const val DEFAULT_MAX_HISTORY_TOKENS = 4000

    /** 中英混合 Token 粗算比例：1 token ≈ 1.5 字符 */
    const val CHARS_PER_TOKEN = 1.5

    /** 截断占位符 */
    private const val TRUNCATION_MARKER = "\n[...已省略超长历史内容...]\n"

    /**
     * 估算给定文本的 Token 数量
     */
    fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    /**
     * 对历史消息列表进行倒序硬预算截断：
     * 1. 空列表直接返回；
     * 2. 从最新消息开始倒序累加 Token 消耗；
     * 3. 超出预算的消息被丢弃；
     * 4. 轮次完整性修剪：截断后若首条消息为 assistant，丢弃该条，确保以 user 开头；
     * 5. 单条极端超长安全保护：头尾保留并插入省略标记。
     */
    fun trim(
        history: List<ChatMessage>,
        maxTokens: Int = DEFAULT_MAX_HISTORY_TOKENS
    ): List<ChatMessage> {
        if (history.isEmpty()) return emptyList()

        // 1. 倒序收集在预算内的消息
        var remainingBudget = maxTokens
        val retainedReversed = mutableListOf<ChatMessage>()

        for (i in history.indices.reversed()) {
            val original = history[i]
            val content = original.content.orEmpty()
            val estimated = estimateTokens(content)

            if (estimated <= remainingBudget) {
                retainedReversed.add(original)
                remainingBudget -= estimated
            } else {
                // 如果是最新的一条消息（retainedReversed 为空）本身就超过了全部预算，进行单条强制安全截断
                if (retainedReversed.isEmpty()) {
                    val safeContent = truncateSingleMessage(content, maxTokens)
                    retainedReversed.add(original.copy(content = safeContent))
                }
                // 预算耗尽，停止向前收集
                break
            }
        }

        val retained = retainedReversed.asReversed()

        // 2. 轮次完整性修剪：确保截断后的历史首条是 user 提问
        // 2. 轮次完整性修剪：确保截断后的历史首条是 user 提问
        val firstUserIndex = retained.indexOfFirst { it.role == "user" }
        if (firstUserIndex == -1) {
            // 如果全部留下的都不是 user（罕见防御），返回空
            return emptyList()
        }

        return if (firstUserIndex == 0) {
            retained
        } else {
            retained.subList(firstUserIndex, retained.size)
        }
    }

    private fun truncateSingleMessage(content: String, maxTokens: Int): String {
        val maxChars = (maxTokens * CHARS_PER_TOKEN).toInt()
        val markerChars = TRUNCATION_MARKER.length
        if (maxChars <= markerChars + 20) {
            return content.take(maxChars.coerceAtLeast(10))
        }
        val half = (maxChars - markerChars) / 2
        val head = content.take(half)
        val tail = content.takeLast(half)
        return "$head$TRUNCATION_MARKER$tail"
    }
}
