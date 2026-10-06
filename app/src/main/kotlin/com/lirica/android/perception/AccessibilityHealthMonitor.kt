package com.lirica.android.perception

import android.content.Context
import android.content.Intent
import com.lirica.android.common.logging.LiricaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍服务健康度（主方案5.0 §7.1 国产四大 ROM 墓碑与保活体系）。
 */
enum class AccessibilityHealth {
    /** 句柄存在且心跳新鲜 */
    CONNECTED,

    /**
     * 僵尸连接：系统开关仍为"已开启"、进程句柄也可能存在，
     * 但已长时间收不到任何无障碍事件（国产 ROM 静默注销的典型形态）。
     */
    STALE,

    /** 句柄丢失（服务被销毁或尚未连接） */
    DISCONNECTED,

    /** 系统设置里已被关闭（用户手动关闭或 ROM 重置）——需引导重新开启 */
    DISABLED_BY_USER
}

/**
 * 无障碍保活健康监视器（M5.2 §7.1）。
 *
 * 判定采用**双证据交叉**，单一证据都不足以定案：
 * 1. **句柄探测**（AccessibilityServiceHolder）：能识别"服务实例没了"；
 * 2. **心跳新鲜度**：能识别"实例还在、事件不来了"的僵尸态；
 * 3. **系统开关**：能区分"被系统/用户关掉"与"进程侧异常"。
 *
 * 为什么不只用 isAccessibilityServiceEnabled：该 API 读的是 Secure 设置里的
 * 字符串列表，ROM 在静默注销时**不会**同步更新它——只看它必然误判为健康。
 */
class AccessibilityHealthMonitor(
    private val handleProvider: () -> Boolean = { AccessibilityServiceHolder.isConnected },
    private val heartbeatProvider: () -> Long = { LiricaAccessibilityService.lastHeartbeatMs },
    /** 系统设置开关探测；可注入是为了让四种健康态在单测里可穷举（Robolectric 下开关恒为关） */
    private val settingsEnabledProvider: (Context) -> Boolean = { ctx ->
        LiricaAccessibilityService.isAccessibilityServiceEnabled(ctx)
    },
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    /** 心跳过期阈值：超过此时长无任何无障碍事件即判僵尸连接 */
    private val staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS
) {

    private val _health = MutableStateFlow(AccessibilityHealth.DISCONNECTED)
    val health: StateFlow<AccessibilityHealth> = _health.asStateFlow()

    /**
     * 采样一次健康度。由保活周期任务或任务分发前调用（零轮询成本设计：
     * 不自起定时器，交由既有的保活/任务链路驱动）。
     */
    fun evaluate(context: Context): AccessibilityHealth {
        val enabledInSettings = runCatching { settingsEnabledProvider(context) }.getOrDefault(false)

        val health = when {
            !enabledInSettings -> AccessibilityHealth.DISABLED_BY_USER
            !handleProvider() -> AccessibilityHealth.DISCONNECTED
            else -> {
                val heartbeat = heartbeatProvider()
                val now = timeProvider()
                if (heartbeat <= 0L || now - heartbeat > staleThresholdMs) {
                    AccessibilityHealth.STALE
                } else {
                    AccessibilityHealth.CONNECTED
                }
            }
        }

        if (health != _health.value) {
            LiricaLog.i("AccessibilityHealthMonitor", "无障碍服务健康度迁移: " + _health.value + " -> " + health)
        }
        _health.value = health
        return health
    }

    /** 任务分发前置检查：非 CONNECTED 时不应派发自动化动作 */
    fun isReadyForAutomation(): Boolean = _health.value == AccessibilityHealth.CONNECTED

    /**
     * 是否允许派发自动化动作（§7.1 第 2 条的前置探测口径）。
     *
     * 只有**开关关闭**与**句柄丢失**才阻断；[AccessibilityHealth.STALE] 仅告警不阻断：
     * 息屏久置本就收不到任何无障碍事件，用"心跳过期"直接拒工会让用户在最需要她的时候
     * 得到一句"我不可用"——而实际上服务好好的。僵尸连接的真正代价由派发失败后的
     * 自愈链路承担，比误拒更可控。
     */
    fun canDispatchAutomation(): Boolean =
        _health.value == AccessibilityHealth.CONNECTED || _health.value == AccessibilityHealth.STALE

    /**
     * 一键引导重开无障碍的 Intent（§7.1 第 2 条）。
     *
     * 刻意只跳到系统设置页而不尝试"自动勾选"：无障碍开关属系统安全边界，
     * 任何自动化代勾选都是越权，且会被 ROM 判定为恶意行为。
     */
    fun createReenableIntent(): Intent = LiricaAccessibilityService.createSettingsIntent()

    companion object {
        /** 默认 15 分钟：既不过度敏感（息屏久置本就没有事件），也能及时发现墓碑 */
        const val DEFAULT_STALE_THRESHOLD_MS: Long = 15 * 60 * 1000L
    }
}
