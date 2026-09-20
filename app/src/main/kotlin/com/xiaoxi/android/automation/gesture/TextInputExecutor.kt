package com.xiaoxi.android.automation.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaoxi.android.automation.grounding.ElementBounds
import kotlinx.coroutines.delay

/**
 * 剪贴板安全文字注入器
 *
 * 优先标准无障碍输入；降级剪贴板时实施毫秒级快照备份与还原，杜绝冲掉用户数据。
 */
class TextInputExecutor(
    private val clipboardManager: ClipboardManager,
    private val gestureExecutor: GestureExecutor
) {
    /**
     * 安全注入文本：
     * 优先路径：标准 ACTION_SET_TEXT (零副作用，不污染剪贴板)
     * 降级路径：剪贴板注入（严守【剪贴板快照恢复】铁律：备份 -> 写入 -> 点击对齐光标 -> 粘贴 -> 恢复原剪贴板）
     */
    suspend fun injectText(
        node: AccessibilityNodeInfo?,
        targetBounds: ElementBounds,
        text: String
    ): Boolean {
        // 1. 优先路径：标准 ACTION_SET_TEXT
        if (node != null && node.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }
        }

        // 2. 降级路径：剪贴板安全注入（严格实施物理快照还原）
        val originalClip: ClipData? = clipboardManager.primaryClip
        return try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("xiaoxi_agent_type", text))
            // 先通过拟人化点击获取输入框光标焦点
            gestureExecutor.execute(GestureSpec.Click(targetBounds.centerX.toFloat(), targetBounds.centerY.toFloat()))
            delay(100)
            node?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
        } finally {
            // 毫秒级恢复用户原本的剪贴板内容，杜绝数据冲掉冒犯
            if (originalClip != null) {
                clipboardManager.setPrimaryClip(originalClip)
            } else {
                clipboardManager.clearPrimaryClip()
            }
        }
    }
}
