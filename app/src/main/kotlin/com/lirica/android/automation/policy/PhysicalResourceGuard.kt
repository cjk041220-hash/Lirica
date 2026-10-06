package com.lirica.android.automation.policy

import com.lirica.android.perception.model.HardwareEnvironmentState

/**
 * 物理资源熔断阈值（主方案5.0 §7.3）。
 */
data class PhysicalGuardThresholds(
    /** 电池温度熔断线：>= 42℃ 时强制熔断连续截屏与后台漫游（方案原文） */
    val maxBatteryTemperatureCelsius: Float = 42.0f,
    /**
     * 热失控全熔断线（工程安全扩展）。
     *
     * 方案只写了 42℃ 熔断视觉通道。此处额外设一条更高水位线：
     * 42~48℃ 之间仍允许轻量 API 动作，>= 48℃ 时连自动化本身也停止——
     * 依据是"物理现实公理"（§1.2.4）：手机不是云端服务器，
     * 在用户口袋里把电池推到 48℃ 换取"帮你看一眼屏幕"是明显不划算的交易。
     * 该扩展不改变方案任何既有指标，只是把不变量的边界收紧。
     */
    val criticalBatteryTemperatureCelsius: Float = 48.0f,
    /** 低电量线：< 20% 且未充电时禁用多模态视觉（方案原文） */
    val lowBatteryPercent: Int = 20
)

/** 物理资源裁决 */
sealed interface PhysicalGuardVerdict {
    /** 全通道可用 */
    data object Allow : PhysicalGuardVerdict

    /** 仅熔断视觉/漫游重通道（截屏、云端 VLM、后台漫游），轻量 API 动作放行 */
    data class BlockVision(val rule: String, val reason: String) : PhysicalGuardVerdict

    /** 全通道熔断（热失控保护） */
    data class BlockAll(val rule: String, val reason: String) : PhysicalGuardVerdict
}

/**
 * 物理资源熔断器（主方案5.0 §7.3 热功耗与电池健康保护）。
 *
 * 纯函数式判定：只读 [HardwareEnvironmentState] 快照，不持有任何系统句柄，
 * 因此可以在纯 JVM 单测里穷举温度/电量组合矩阵。
 */
class PhysicalResourceGuard(
    private val stateProvider: () -> HardwareEnvironmentState,
    private val thresholds: PhysicalGuardThresholds = PhysicalGuardThresholds()
) {

    fun evaluate(): PhysicalGuardVerdict {
        val state = stateProvider()

        val temperature = state.batteryTemperatureCelsius
        if (temperature != null && temperature >= thresholds.criticalBatteryTemperatureCelsius) {
            return PhysicalGuardVerdict.BlockAll(
                rule = RULE_THERMAL_CRITICAL,
                reason = "电池温度 " + temperature + "℃ 已达热失控保护线 (" +
                    thresholds.criticalBatteryTemperatureCelsius + "℃)，停止全部自动化"
            )
        }
        if (temperature != null && temperature >= thresholds.maxBatteryTemperatureCelsius) {
            return PhysicalGuardVerdict.BlockVision(
                rule = RULE_THERMAL_VISION_FUSE,
                reason = "电池温度 " + temperature + "℃ 已达 " + thresholds.maxBatteryTemperatureCelsius +
                    "℃，熔断连续截屏与后台漫游"
            )
        }

        val level = state.batteryLevelPercent
        if (level != null && level < thresholds.lowBatteryPercent && !state.isCharging) {
            return PhysicalGuardVerdict.BlockVision(
                rule = RULE_LOW_BATTERY_VISION_FUSE,
                reason = "电量 " + level + "% 且未充电，按电量梯度限流：禁用多模态视觉，仅放行轻量动作"
            )
        }

        return PhysicalGuardVerdict.Allow
    }

    /** 是否允许截屏 / 云端 VLM / 后台漫游 */
    fun isVisionAllowed(): Boolean = evaluate() is PhysicalGuardVerdict.Allow

    /** 是否允许派发任何自动化动作 */
    fun isAutomationAllowed(): Boolean = evaluate() !is PhysicalGuardVerdict.BlockAll

    companion object {
        const val RULE_THERMAL_CRITICAL = "RULE_THERMAL_CRITICAL_FUSE"
        const val RULE_THERMAL_VISION_FUSE = "RULE_THERMAL_VISION_FUSE"
        const val RULE_LOW_BATTERY_VISION_FUSE = "RULE_LOW_BATTERY_VISION_FUSE"
    }
}
