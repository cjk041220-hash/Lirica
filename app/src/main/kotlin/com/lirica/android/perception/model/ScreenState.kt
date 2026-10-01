package com.lirica.android.perception.model

/**
 * 屏幕物理与交互状态枚举
 */
enum class ScreenState {
    /** 息屏待机状态 */
    OFF,
    /** 亮屏但尚未解锁 (锁屏页) */
    ON,
    /** 用户已解锁并呈现前台 (交互状态) */
    UNLOCKED
}

/**
 * 屏幕状态快照与时长聚合
 * @param state 当前屏幕状态
 * @param lastStateChangeTimestamp 最近一次状态变更时间戳 (毫秒)
 * @param totalInteractiveDurationMs 累计交互活跃时长 (毫秒，ON + UNLOCKED)
 * @param totalStandbyDurationMs 累计息屏待机时长 (毫秒，OFF)
 * @param lastUserPresentTimestamp 用户**上一次真正面对手机**的时刻（息屏/锁屏时写入）。
 *
 * 这个字段是"离开了多久"的唯一可靠来源。不能用 lastStateChangeTimestamp 代替：
 * 解锁动作本身就会刷新那个戳（OFF→ON→UNLOCKED），于是"离开 6 小时后刚解锁"算出来是 0 秒，
 * 而"连续亮屏 6 小时"反而被算成离开 6 小时——语义正好反了。
 */
data class ScreenStateSnapshot(
    val state: ScreenState = ScreenState.OFF,
    val lastStateChangeTimestamp: Long = 0L,
    val totalInteractiveDurationMs: Long = 0L,
    val totalStandbyDurationMs: Long = 0L,
    val lastUserPresentTimestamp: Long = 0L
)
