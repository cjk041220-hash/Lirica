package com.lirica.android.automation.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.automation.gatekeeper.FinancialGatekeeper
import com.lirica.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.lirica.android.automation.gatekeeper.GatekeeperDecision
import com.lirica.android.automation.gatekeeper.ProposedAction
import com.lirica.android.automation.grounding.ElementBounds
import kotlinx.coroutines.delay

sealed interface TextInputResult {
    val isSuccess: Boolean
    val honestReason: String?

    data object Success : TextInputResult {
        override val isSuccess: Boolean = true
        override val honestReason: String? = null
    }

    data class Rejected(
        override val honestReason: String,
        val rule: String,
        val technicalReason: String
    ) : TextInputResult {
        override val isSuccess: Boolean = false
    }

    data class Failed(
        val reason: String,
        override val honestReason: String? = null
    ) : TextInputResult {
        override val isSuccess: Boolean = false
    }
}

/**
 * 剪贴板安全文字注入器
 *
 * 优先标准无障碍输入；降级剪贴板时实施毫秒级快照备份与还原，杜绝冲掉用户数据。
 * 执行动作前经 FinancialGatekeeper 前置校验，命中禁止区直接拒绝并携带诚实原因。
 */
class TextInputExecutor(
    private val clipboardManager: ClipboardManager,
    private val gestureExecutor: GestureExecutor,
    private val gatekeeper: FinancialGatekeeper = FinancialGatekeeperImpl(),
    private val currentPackageProvider: () -> String? = { null }
) {
    var lastResult: TextInputResult? = null
        private set

    val lastHonestReason: String?
        get() = lastResult?.honestReason

    /**
     * 向后兼容 3 参数重载
     */
    suspend fun injectText(
        node: AccessibilityNodeInfo?,
        targetBounds: ElementBounds,
        text: String
    ): Boolean = injectText(node, targetBounds, text, null)

    /**
     * 安全注入文本（返回布尔值）
     */
    suspend fun injectText(
        node: AccessibilityNodeInfo?,
        targetBounds: ElementBounds,
        text: String,
        packageName: String?
    ): Boolean {
        val result = executeInject(node, targetBounds, text, packageName)
        return result.isSuccess
    }

    /**
     * 执行文本注入并返回详细结构化结果 (TextInputResult)
     *
     * 0. 前置安全校验：将目标包名与控件上下文提交 FinancialGatekeeper 判定；命中禁止区直接拒绝
     * 1. 优先路径：标准 ACTION_SET_TEXT (零副作用，不污染剪贴板)
     * 2. 降级路径：剪贴板注入（严守【剪贴板快照恢复】铁律：备份 -> 写入 -> 点击对齐光标 -> 粘贴 -> 恢复原剪贴板）
     */
    suspend fun executeInject(
        node: AccessibilityNodeInfo?,
        targetBounds: ElementBounds,
        text: String,
        packageName: String? = null
    ): TextInputResult {
        // 0. 执行前置安全门控校验
        val pkg = packageName?.takeIf { it.isNotBlank() }
            ?: node?.packageName?.toString()
            ?: currentPackageProvider()
            ?: ""

        val isPassword = (node?.isPassword == true)
            || (node?.viewIdResourceName?.lowercase()?.let { it.contains("password") || it.contains("pwd") || it.contains("pin") } == true)

        val proposed = ProposedAction(
            actionType = "INPUT",
            targetText = node?.text?.toString() ?: text,
            targetResourceId = node?.viewIdResourceName,
            isPasswordTarget = isPassword,
            currentPackage = pkg,
            extraInfo = mapOf("inputText" to text)
        )

        val decision = gatekeeper.evaluate(proposed)
        if (decision is GatekeeperDecision.PhysicalBlocked) {
            val rejected = TextInputResult.Rejected(
                honestReason = decision.honestReason,
                rule = decision.rule,
                technicalReason = decision.reason
            )
            lastResult = rejected
            return rejected
        }

        // 1. 优先路径：标准 ACTION_SET_TEXT
        if (node != null && node.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                val success = TextInputResult.Success
                lastResult = success
                return success
            }
        }

        // 2. 降级路径：剪贴板安全注入（严格实施物理快照还原）
        val originalClip: ClipData? = clipboardManager.primaryClip
        return try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("lirica_agent_type", text))
            // 先通过拟人化点击获取输入框光标焦点
            val clickSpec = GestureSpec.Click(
                x = targetBounds.centerX.toFloat(),
                y = targetBounds.centerY.toFloat(),
                targetPackage = pkg,
                targetResourceId = node?.viewIdResourceName,
                isPasswordTarget = isPassword
            )
            val gestureResult = gestureExecutor.execute(clickSpec)
            if (gestureResult is GestureResult.Cancelled && (gestureResult.reason.contains("受限") || gestureResult.reason.contains("blocked"))) {
                val rejected = TextInputResult.Rejected(
                    honestReason = gestureResult.reason,
                    rule = "GESTURE_BLOCKED",
                    technicalReason = gestureResult.reason
                )
                lastResult = rejected
                return rejected
            }
            delay(100)
            val pasteSuccess = node?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
            if (pasteSuccess) {
                TextInputResult.Success.also { lastResult = it }
            } else {
                TextInputResult.Failed("ACTION_PASTE 执行返回失败").also { lastResult = it }
            }
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
