package com.lirica.android.automation.verify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用户交互态（主方案5.0 §6.2 用户抢占熔断）。
 */
enum class UserInteractionState {
    /** 屏幕安静，自动化可自由派发 */
    IDLE,

    /** 检测到人工触控/接管迹象，自动化必须立即暂停派发 */
    USER_ACTIVE
}

/**
 * 抢占判定来源（多通道冗余 · 复审 R4 降级备选落地）。
 */
enum class InterruptionSource {
    NONE,

    /** 主通道：无障碍 TYPE_TOUCH_INTERACTION_START 事件 */
    TOUCH_EVENT,

    /** 降级通道 A：前台包名/页面指纹在非我方动作期间发生突变 */
    FOREGROUND_TAKEOVER,

    /** 降级通道 B：dispatchGesture 完成回调异常（真实手指在屏会取消注入手势） */
    GESTURE_ANOMALY
}

/**
 * 用户抢占熔断器（主方案5.0 §6.2 · 复审 R4 全量吸收）。
 *
 * 方案原文只写了"注册 TYPE_TOUCH_INTERACTION_START，捕获未由自动化引擎发起的
 * 人工触控"。落地时必须补齐方案没写的三件事，否则真机上会立刻出问题：
 *
 * **1. 自触发抑制（最关键）**
 * 无障碍 dispatchGesture 注入的手势**同样**会产生 TYPE_TOUCH_INTERACTION_START。
 * 不加以区分，"她一动手就以为用户抢了手机"，任务必然在第 1 步就自我中断。
 * 因此每次派发手势前登记抑制窗口 [onAutomationGestureDispatched]，
 * 窗口内的触控事件一律判为我方自产。
 *
 * **2. 事件可达性不确定（R4）**
 * 该事件对无障碍服务的分发条件与服务声明何种 capability 相关，且国产 ROM
 * 可靠性未经验证。因此本检测器是**多通道冗余**的：即使一条事件都收不到，
 * 仍可由前台接管突变与手势回调异常两条降级通道触发熔断。
 *
 * **3. "用户停手 ≠ 用户同意继续"（R4 裁决）**
 * 方案原写"待用户停止操作 5 秒后恢复执行"，复审建议改为**默认不自动恢复**。
 * 本实现遵此：[autoResumeAfterQuietMs] 默认为 null（不自动恢复），
 * 只有显式 [requestResume]（悬浮胶囊"继续"或对话确认）才解除。
 */
class UserInterruptionDetector(
    /** 用户停手多久视为"安静"（仅用于自动恢复模式与状态展示） */
    private val quietThresholdMs: Long = DEFAULT_QUIET_THRESHOLD_MS,
    /** 我方手势结束后额外屏蔽多久的触控事件（手指抬起抖动） */
    private val selfTouchSuppressionMs: Long = DEFAULT_SELF_TOUCH_SUPPRESSION_MS,
    /**
     * 自动恢复静默期；null = 不自动恢复（默认，遵 R4 裁决）。
     * 仅当业务显式配置时才启用，且必须由用户可见的设置项控制。
     */
    private val autoResumeAfterQuietMs: Long? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private val _state = MutableStateFlow(UserInteractionState.IDLE)
    val state: StateFlow<UserInteractionState> = _state.asStateFlow()

    private val _source = MutableStateFlow(InterruptionSource.NONE)
    val source: StateFlow<InterruptionSource> = _source.asStateFlow()

    @Volatile
    private var suppressionUntilMs: Long = 0L

    @Volatile
    private var lastUserActivityAtMs: Long = 0L

    @Volatile
    private var lastKnownForegroundPackage: String? = null

    /** 自动化派发手势前登记抑制窗口（自触发抑制） */
    fun onAutomationGestureDispatched(estimatedDurationMs: Long) {
        val until = timeProvider() + estimatedDurationMs.coerceAtLeast(0L) + selfTouchSuppressionMs
        if (until > suppressionUntilMs) suppressionUntilMs = until
    }

    /**
     * 无障碍触控事件入口。
     *
     * @return true 表示判定为人工触控并已进入 [UserInteractionState.USER_ACTIVE]；
     *         false 表示属于我方注入手势或已被抑制，调用方应忽略。
     */
    fun onPhysicalTouch(atMs: Long = timeProvider()): Boolean {
        if (atMs <= suppressionUntilMs) return false
        raise(InterruptionSource.TOUCH_EVENT, atMs)
        return true
    }

    /**
     * 降级通道 A：每步 Observe 时把当前前台包名喂进来。
     *
     * 在两次动作之间前台应用发生了非我方发起的切换，是"用户接管手机"的强信号
     * （弹广告、网络推送等噪音不会切换前台应用包名）。
     */
    fun onForegroundObserved(packageName: String?, atMs: Long = timeProvider()): Boolean {
        val previous = lastKnownForegroundPackage
        lastKnownForegroundPackage = packageName
        if (previous == null || packageName == null) return false
        if (previous == packageName) return false
        raise(InterruptionSource.FOREGROUND_TAKEOVER, atMs)
        return true
    }

    /** 登记"我方主动切换了前台应用"，避免把自身的应用拉起误判为用户接管 */
    fun onAutomationForegroundChange(packageName: String?) {
        lastKnownForegroundPackage = packageName
    }

    /**
     * 降级通道 B：手势派发回调异常（真实手指在屏时系统会取消注入手势）。
     */
    fun onGestureAnomaly(atMs: Long = timeProvider()): Boolean {
        raise(InterruptionSource.GESTURE_ANOMALY, atMs)
        return true
    }

    private fun raise(source: InterruptionSource, atMs: Long) {
        lastUserActivityAtMs = atMs
        _source.value = source
        _state.value = UserInteractionState.USER_ACTIVE
    }

    fun isUserActive(): Boolean = _state.value == UserInteractionState.USER_ACTIVE

    /** 用户显式点击"继续"或对话确认后调用 */
    fun requestResume() {
        _source.value = InterruptionSource.NONE
        _state.value = UserInteractionState.IDLE
    }

    /**
     * 时间推进（由执行循环每步调用，避免内部起线程/定时器）。
     *
     * @return true 表示本次刷新把状态从 USER_ACTIVE 收敛回 IDLE
     */
    fun refresh(now: Long = timeProvider()): Boolean {
        if (_state.value != UserInteractionState.USER_ACTIVE) return false
        val autoResume = autoResumeAfterQuietMs ?: return false
        if (now - lastUserActivityAtMs < autoResume) return false
        requestResume()
        return true
    }

    /** 用户停手时长（用于悬浮层展示与自愈策略） */
    fun quietDurationMs(now: Long = timeProvider()): Long =
        if (lastUserActivityAtMs == 0L) 0L else (now - lastUserActivityAtMs).coerceAtLeast(0L)

    /**
     * 挂起等待解除抢占。
     *
     * @return true 表示已解除（用户点继续 / 可配置静默自动恢复），false 表示超时
     */
    suspend fun awaitClearance(timeoutMs: Long): Boolean {
        if (_state.value == UserInteractionState.IDLE) return true
        return withTimeoutOrNull(timeoutMs) {
            _state.first { it == UserInteractionState.IDLE }
            true
        } ?: false
    }

    companion object {
        /** 方案 §6.2 的"停止操作 5 秒"口径（仅用于自动恢复模式） */
        const val DEFAULT_QUIET_THRESHOLD_MS: Long = 5_000L

        /** 我方手势结束后额外屏蔽窗口：覆盖手指抬起抖动与系统事件延迟 */
        const val DEFAULT_SELF_TOUCH_SUPPRESSION_MS: Long = 300L
    }
}
