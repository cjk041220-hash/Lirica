package com.xiaoxi.android.automation.backend

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent

/**
 * 符号化系统按键名称与 Android KeyEvent 键码映射器
 *
 * 解决大模型直接生成裸整型 keycode 不可靠的问题，允许模型使用清晰的符号名称（如 "ENTER"、"BACK" 等）。
 */
object KeycodeMapper {

    private val KEY_NAME_TO_CODE = mapOf(
        "BACK" to KeyEvent.KEYCODE_BACK,
        "RETURN" to KeyEvent.KEYCODE_BACK,
        "返回" to KeyEvent.KEYCODE_BACK,
        "HOME" to KeyEvent.KEYCODE_HOME,
        "主页" to KeyEvent.KEYCODE_HOME,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "确认" to KeyEvent.KEYCODE_ENTER,
        "回车" to KeyEvent.KEYCODE_ENTER,
        "DEL" to KeyEvent.KEYCODE_DEL,
        "DELETE" to KeyEvent.KEYCODE_DEL,
        "删除" to KeyEvent.KEYCODE_DEL,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "空格" to KeyEvent.KEYCODE_SPACE,
        "SEARCH" to KeyEvent.KEYCODE_SEARCH,
        "搜索" to KeyEvent.KEYCODE_SEARCH,
        "RECENTS" to KeyEvent.KEYCODE_APP_SWITCH,
        "APP_SWITCH" to KeyEvent.KEYCODE_APP_SWITCH,
        "多任务" to KeyEvent.KEYCODE_APP_SWITCH,
        "VOLUME_UP" to KeyEvent.KEYCODE_VOLUME_UP,
        "音量加" to KeyEvent.KEYCODE_VOLUME_UP,
        "VOLUME_DOWN" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "音量减" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "POWER" to KeyEvent.KEYCODE_POWER,
        "电源" to KeyEvent.KEYCODE_POWER,
        "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT
    )

    /**
     * 将按键符号名或字符串格式的整数解析为 Android KeyEvent 键码。
     *
     * @param keyNameOrCode 符号名（如 "BACK", "ENTER"）或纯数字字面量（如 "66"）
     * @return 对应的按键码，无法解析时返回 null
     */
    fun resolveKeyCode(keyNameOrCode: String): Int? {
        val trimmed = keyNameOrCode.trim()
        if (trimmed.isEmpty()) return null

        // 纯数字键码直接解析
        trimmed.toIntOrNull()?.let { return it }

        // 符号名称匹配
        return KEY_NAME_TO_CODE[trimmed.uppercase()]
            ?: KEY_NAME_TO_CODE[trimmed]
    }

    /**
     * 将按键码映射为 AccessibilityService 的全局动作码（用于无障碍降级兜底）。
     */
    fun toAccessibilityGlobalAction(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
            KeyEvent.KEYCODE_POWER -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }
    }
}
