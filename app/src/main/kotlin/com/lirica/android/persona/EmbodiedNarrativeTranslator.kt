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

    /**
     * 离开多久算"久别"：5 小时。
     * 与 CareHintDetector.RETURNED 的"按天计缺席"是两个尺度，互不冲突：
     * 这里管"你出门一下午回来"，那边管"你好几天没出现"。
     */
    const val LONG_ABSENCE_THRESHOLD_MS = 5 * 3600 * 1000L

    /**
     * @param absenceDurationMs 距用户上一次真正面对手机的时长（息屏/锁屏期间累积）。
     *        这是"久别"的唯一正确输入：用屏幕状态变更时刻或前台停留时长都会算反
     *        （解锁动作本身会刷新状态戳，而停留时长反映的是"一直在用"）。
     * @param dwellDurationMs 兼容参数，保留旧签名调用方；不参与久别判定。
     */
    fun translate(
        hw: HardwareEnvironmentState?,
        screen: ScreenStateSnapshot?,
        hourOfDay: Int,
        dwellDurationMs: Long = 0L,
        absenceDurationMs: Long = 0L
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
        // 判定口径：用户确实离开过（absenceDurationMs 由息屏/锁屏时长累积），
        // 且此刻已经回到手机前（UNLOCKED）。与"连续亮屏 5 小时"完全不沾边——
        // 那种情况是用户在长时间使用，不该被说成"手机沉睡了好久"。
        if (absenceDurationMs >= LONG_ABSENCE_THRESHOLD_MS && screen?.state == ScreenState.UNLOCKED) {
            fragments.add("手机安静沉睡了好久，刚才终于重新解锁了")
        }

        return fragments.joinToString("；") { "【具身情境：$it】" }
    }
}