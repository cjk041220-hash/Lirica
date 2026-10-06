package com.lirica.android.perception.model

/**
 * 硬件环境态快照（M3.4 硬件环境态总线 / M5.2 物理防御扩展）。
 *
 * 聚合通话状态、耳机插拔、充电状态与音频播放状态，
 * 为场景分类与打扰度代价计算 (InterruptibilityEstimator) 提供物理上下文信号。
 *
 * 2026-10-06 M5.2 扩展（主方案5.0 §7.3 热功耗与电池健康保护）：
 * 新增电池温度、电量百分比与光照强度三项。前两项用于
 * PhysicalResourceGuard 的熔断判定（温度 >= 42℃ 禁截屏/漫游；
 * 电量 < 20% 且未充电时禁用多模态视觉），光照强度用于夜间漫游的
 * 亮度自适应（§7.3 与 §八 夜间独立 Display 漫游的前置信号）。
 *
 * 三项均为可空：部分 ROM 不广播温度、无光传感器的设备也不在少数，
 * 用"未知"而不是"默认安全值"表达缺失——把未知当安全是安全设计的经典错误。
 */
data class HardwareEnvironmentState(
    val isPhoneInCall: Boolean = false,
    val isHeadsetPlugged: Boolean = false,
    val isCharging: Boolean = false,
    val isAudioPlaying: Boolean = false,
    /** 电池温度（摄氏度）；null = 该设备/该 ROM 未提供 */
    val batteryTemperatureCelsius: Float? = null,
    /** 电池电量百分比 0~100；null = 未知 */
    val batteryLevelPercent: Int? = null,
    /** 环境光照强度（lux）；null = 无光传感器或未注册监听 */
    val ambientLightLux: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)
