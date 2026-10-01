package com.lirica.android.persona

import com.lirica.android.perception.model.HardwareEnvironmentState
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot

/** 
 * 具身生活叙事转译器 (Embodied Narrative Translator - M4.3 核心)
 *
 * 核心职能：
 * 将冷冰冰的硬件传感器指标（耳机、电量、作息）转译为第一人称的生理与具身共情叙事，
 * 供 MacroExpander 与 PhiMiddleware 在末端就地展开，增强“共用这台手机的邻居”生活呼吸感。
 */
object EmbodiedNarrativeTranslator {

    fun translate(
        hw: HardwareEnvironmentState?,
        screen: ScreenStateSnapshot?,
        hourOfDay: Int,
        dwellDurationMs: Long = 0L
    ): String {
        val fragments = mutableListOf<String>()

        // 1. 耳机与媒体共情
        if (hw != null && hw.isHeadsetPlugged && hw.isAudioPlaying) {
            fragments.add("你戴着耳机听音乐，沉浸在自己的小世界里")
        }

        // 2. 深夜疲劳与作息感知
        if (hourOfDay in 0..4 && screen?.state == ScreenState.UNLOCKED) {
            fragments.add("已经是深夜 ${hourOfDay} 点了，屏幕还亮着，想督促你早点休息")
        }

        // 3. 晨间唤醒情境
        if (hourOfDay in 6..9 && screen?.state == ScreenState.UNLOCKED && dwellDurationMs < 60000L) {
            fragments.add("清晨刚解锁手机，新的一天开始啦")
        }

        // 4. 长时间离线后重聚
        if (dwellDurationMs >= 5 * 3600 * 1000L && screen?.state == ScreenState.UNLOCKED) {
            fragments.add("手机安静沉睡了好久，刚才终于重新解锁了")
        }

        return fragments.joinToString("；") { "【具身情境：$it】" }
    }
}