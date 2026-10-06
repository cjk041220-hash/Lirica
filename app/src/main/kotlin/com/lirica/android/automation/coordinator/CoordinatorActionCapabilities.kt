package com.lirica.android.automation.coordinator

/**
 * 动作是否依赖无障碍服务（§7.1 任务分发前置探测的判据）。
 *
 * 纯 Intent / 系统 API 动作（闹钟、日历、手电筒、音量、亮度）不碰语义树，
 * 无障碍被系统或省电策略关掉时它们仍然可用——把这部分一并拦下属于过度耦合，
 * 会让用户产生"她彻底坏了"的错觉（连闹钟都设不了）。
 */
fun CoordinatorAction.requiresAccessibility(): Boolean = when (this) {
    is CoordinatorAction.Click,
    is CoordinatorAction.Type,
    is CoordinatorAction.Swipe,
    is CoordinatorAction.Backtrack,
    is CoordinatorAction.PressKey -> true

    // 延迟与收尾不接触界面；Intent 走系统通道
    is CoordinatorAction.Delay,
    is CoordinatorAction.Finish,
    is CoordinatorAction.Intent -> false
}
