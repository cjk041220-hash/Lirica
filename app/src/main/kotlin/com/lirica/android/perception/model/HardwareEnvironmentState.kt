package com.lirica.android.perception.model

/**
 * 硬件环境态快照（M3.4 硬件环境态总线）。
 *
 * 聚合通话状态、耳机插拔、充电状态与音频播放状态，
 * 为场景分类与打扰度代价计算 (InterruptibilityEstimator) 提供物理上下文信号。
 */
data class HardwareEnvironmentState(
    val isPhoneInCall: Boolean = false,
    val isHeadsetPlugged: Boolean = false,
    val isCharging: Boolean = false,
    val isAudioPlaying: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
