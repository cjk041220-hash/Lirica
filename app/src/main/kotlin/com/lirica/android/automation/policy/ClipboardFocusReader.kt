package com.lirica.android.automation.policy

import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板前台焦点读取器（主方案5.0 §4.1 P2-6 现实边界）。
 *
 * Android 10（API 29）起系统禁止后台应用静默读取剪贴板：无焦点时
 * getPrimaryClip() 返回 null 或空剪贴。方案明文禁止任何静默后台轮询设计，
 * 因此本读取器把"合法场景"编码成入参而非内部猜测：
 * 1. 用户主动分享给 Lirica（isUserInitiatedShare = true）；
 * 2. Lirica 悬浮窗或主界面获得系统焦点的瞬间（hasWindowFocus && isForeground）。
 *
 * 其余一切调用一律 [ClipboardReadResult.Blocked]，且理由走
 * RULE_CLIPBOARD_FOREGROUND_ONLY，可在回归用例中断言。
 */
interface ClipboardFocusReader {
    fun read(request: SafetyRequest.ClipboardRead): ClipboardReadResult
}

sealed interface ClipboardReadResult {
    data class Success(val text: String) : ClipboardReadResult

    /** 合法读取但剪贴板为空/非文本内容 */
    data object Empty : ClipboardReadResult

    data class Blocked(val rule: String, val honestReason: String) : ClipboardReadResult

    data class Failed(val reason: String) : ClipboardReadResult
}

class ClipboardFocusReaderImpl(
    private val context: Context,
    private val safetyPolicy: SafetyPolicyEngine
) : ClipboardFocusReader {

    override fun read(request: SafetyRequest.ClipboardRead): ClipboardReadResult {
        val decision = safetyPolicy.evaluate(request)
        if (decision is SafetyDecision.Blocked) {
            return ClipboardReadResult.Blocked(decision.rule, decision.honestReason)
        }
        return runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return ClipboardReadResult.Failed("设备无 ClipboardManager 服务")
            if (!manager.hasPrimaryClip()) return ClipboardReadResult.Empty
            val clip = manager.primaryClip ?: return ClipboardReadResult.Empty
            if (clip.itemCount == 0) return ClipboardReadResult.Empty
            val text = clip.getItemAt(0).coerceToText(context)?.toString()
            if (text.isNullOrEmpty()) ClipboardReadResult.Empty else ClipboardReadResult.Success(text)
        }.getOrElse { ClipboardReadResult.Failed(it.message ?: it::class.simpleName ?: "unknown") }
    }
}
