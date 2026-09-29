package com.lirica.android.automation.backend

import com.lirica.android.common.logging.LiricaLog

/**
 * 双通道混合自动化执行中枢 (HybridExecutionManager)
 *
 * 核心调度策略：
 * 1. 点击/滑动/长按/按键：优先走 Shizuku 高速穿透通道（时延极低，穿透自绘 Canvas 与游戏界面），
 *    未激活时自动优雅降级为 AccessibilityService 拟人三次贝塞尔手势；
 * 2. 中文输入分流铁律 (T4.4)：ADB input text 依赖 KeyCharacterMap 无法键入 CJK 字符与 Emoji，
 *    包含中文或 Emoji 时 100% 分流至无障碍 TextInputExecutor（ACTION_SET_TEXT 或剪贴板快照还原输入）；
 * 3. 纯 ASCII 时若 Shizuku 可用则优先使用，执行失败自动回退无障碍。
 */
class HybridExecutionManager(
    private val accessibilityBackend: ExecutionBackend,
    private val shizukuBackend: ExecutionBackend? = null
) : ExecutionBackend {

    override suspend fun isAvailable(): Boolean {
        return (shizukuBackend?.isAvailable() == true) || accessibilityBackend.isAvailable()
    }

    override suspend fun click(x: Float, y: Float): Boolean {
        if (shizukuBackend?.isAvailable() == true) {
            val ok = shizukuBackend.click(x, y)
            if (ok) return true
            LiricaLog.w("HybridExecutionManager", "Shizuku 点击失败，降级无障碍手势")
        }
        return accessibilityBackend.click(x, y)
    }

    override suspend fun longClick(x: Float, y: Float, durationMs: Long): Boolean {
        if (shizukuBackend?.isAvailable() == true) {
            val ok = shizukuBackend.longClick(x, y, durationMs)
            if (ok) return true
            LiricaLog.w("HybridExecutionManager", "Shizuku 长按失败，降级无障碍手势")
        }
        return accessibilityBackend.longClick(x, y, durationMs)
    }

    override suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        if (shizukuBackend?.isAvailable() == true) {
            val ok = shizukuBackend.swipe(startX, startY, endX, endY, durationMs)
            if (ok) return true
            LiricaLog.w("HybridExecutionManager", "Shizuku 滑动失败，降级无障碍手势")
        }
        return accessibilityBackend.swipe(startX, startY, endX, endY, durationMs)
    }

    override suspend fun sendKeycode(keyCode: Int): Boolean {
        if (shizukuBackend?.isAvailable() == true) {
            val ok = shizukuBackend.sendKeycode(keyCode)
            if (ok) return true
            LiricaLog.w("HybridExecutionManager", "Shizuku 按键失败，降级无障碍全局动作")
        }
        return accessibilityBackend.sendKeycode(keyCode)
    }

    override suspend fun inputText(text: String): Boolean {
        // T4.4 铁律：中文或 Emoji 字符 100% 走无障碍通道
        if (containsCjkOrSpecial(text)) {
            return accessibilityBackend.inputText(text)
        }

        // 纯 ASCII 文本优先走 Shizuku
        if (shizukuBackend?.isAvailable() == true) {
            val ok = shizukuBackend.inputText(text)
            if (ok) return true
            LiricaLog.w("HybridExecutionManager", "Shizuku 文本注入失败，降级无障碍通道")
        }
        return accessibilityBackend.inputText(text)
    }

    companion object {
        /**
         * 检测字符串是否包含中文字符、CJK 符号或 Emoji 代理项
         */
        fun containsCjkOrSpecial(text: String): Boolean {
            for (char in text) {
                if (Character.isSurrogate(char)) return true
                val ub = Character.UnicodeBlock.of(char)
                if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                    ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    ub == Character.UnicodeBlock.GENERAL_PUNCTUATION ||
                    ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                    ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                ) {
                    return true
                }
            }
            return false
        }
    }
}
