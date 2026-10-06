package com.lirica.android.common.settings

import android.content.Context
import android.content.SharedPreferences
import com.lirica.android.automation.policy.ActionExecutionMode

/**
 * 双态行动模式持久化（M5.1 §八）。
 *
 * 为什么单独开一个 SharedPreferences 文件而不是塞进 BYOK 设置：
 * BYOK 里全是密钥与模型配置，行动模式是**行为开关**，两者的读取时机与
 * 失效语义完全不同（模式要在 Application.onCreate 阶段就被读到并生效）。
 * 只有两个取值、单值读取，不值得为它付一次 Room schema 迁移。
 */
class ActionModeSettings private constructor(
    private val prefs: SharedPreferences
) {

    fun load(): ActionExecutionMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return ActionExecutionMode.SHARED
        return runCatching { ActionExecutionMode.valueOf(raw) }.getOrDefault(ActionExecutionMode.SHARED)
    }

    fun save(mode: ActionExecutionMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        const val PREFS_NAME = "lirica_action_mode_prefs"
        const val KEY_MODE = "action_execution_mode"

        fun create(context: Context): ActionModeSettings = ActionModeSettings(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }
}
