package com.lirica.android.perception.model

/**
 * 统一多维端侧情境感知快照
 *
 * @param timestamp 捕获时间戳 (毫秒)
 * @param foregroundApp 当前前台应用及停留时长 (Task 3.1)
 * @param screenState 当前屏幕物理与待机状态 (Task 3.1)
 * @param latestNotification 最近一次接收的有效通知 (Task 3.2)
 * @param pageContent 经过隐私熔断清洗后的页面无障碍节点快照 (Task 3.3 + Task 3.4)
 * @param isPrivacyFusing 当前是否正处于隐私保护熔断状态 (银行/支付/密码)
 * @param perceivedSummary 综合自然语言情境感知一句话摘要
 */
data class PerceptionSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val foregroundApp: ForegroundAppInfo? = null,
    val screenState: ScreenStateSnapshot = ScreenStateSnapshot(),
    val latestNotification: NotificationEvent? = null,
    val pageContent: PageContentSnapshot? = null,
    val isPrivacyFusing: Boolean = false,
    val hardwareEnvironment: HardwareEnvironmentState = HardwareEnvironmentState(),
    val perceivedSummary: String = ""
)
