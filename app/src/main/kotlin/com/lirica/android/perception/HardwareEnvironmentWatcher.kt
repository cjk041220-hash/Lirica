package com.lirica.android.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.lirica.android.perception.model.HardwareEnvironmentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 硬件环境态监视器（M3.4 硬件环境态总线）。
 *
 * 核心职能：
 * 监听广播与音频系统服务，捕获耳机插入、通话模式、充电状态与媒体播放状态，
 * 免受危险权限申请限制（全部使用 AOSP 基础广播与 AudioManager 状态）。
 *
 * 2026-09-27 审计 P1 修复：通话/媒体状态不再只在 start() 采样一次——
 * 亮屏、解锁、音频路由变化（AUDIO_BECOMING_NOISY，如拔耳机）时重新查询
 * AudioManager，保证 InterruptibilityEstimator 的通话物理熔断持续有效。
 * 已知近似：通话开始瞬间若无上述广播事件，状态将在下一次亮屏/路由事件时刷新
 * （零轮询成本与实时性的折中；完整方案需 OnModeChangeListener，见后续迭代）。
 */
class HardwareEnvironmentWatcher {

    private val _hardwareState = MutableStateFlow(HardwareEnvironmentState())
    val hardwareState: StateFlow<HardwareEnvironmentState> = _hardwareState.asStateFlow()

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    fun start(context: Context) {
        if (receiver != null) return

        val initialCharging = checkChargingStatus(context)
        val initialCall = checkCallStatus(context)
        val initialMusic = checkMusicActive(context)

        _hardwareState.update {
            it.copy(
                isCharging = initialCharging,
                isPhoneInCall = initialCall,
                isAudioPlaying = initialMusic,
                timestamp = System.currentTimeMillis()
            )
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            // 音频/屏幕事件驱动通话与媒体状态动态刷新
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }

        val bReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null || ctx == null) return
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        _hardwareState.update { it.copy(isCharging = true, timestamp = System.currentTimeMillis()) }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        _hardwareState.update { it.copy(isCharging = false, timestamp = System.currentTimeMillis()) }
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", -1)
                        val isPlugged = state == 1
                        _hardwareState.update { it.copy(isHeadsetPlugged = isPlugged, timestamp = System.currentTimeMillis()) }
                    }
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT,
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        refreshAudioState(ctx)
                    }
                }
            }
        }

        runCatching {
            // 系统受保护广播 + 音频路由广播，显式 NOT_EXPORTED 不依赖隐式豁免
            ContextCompat.registerReceiver(
                context,
                bReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiver = bReceiver
            appContext = context.applicationContext
        }
    }

    fun stop(context: Context) {
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
            receiver = null
            appContext = null
        }
    }

    internal fun updateStateForTest(newState: HardwareEnvironmentState) {
        _hardwareState.value = newState
    }

    /**
     * 重新查询通话与媒体播放状态并广播到状态总线。
     */
    private fun refreshAudioState(context: Context) {
        val inCall = checkCallStatus(context)
        val music = checkMusicActive(context)
        _hardwareState.update {
            it.copy(
                isPhoneInCall = inCall,
                isAudioPlaying = music,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun checkChargingStatus(context: Context): Boolean {
        return runCatching {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }.getOrDefault(false)
    }

    private fun checkCallStatus(context: Context): Boolean {
        return runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)
    }

    private fun checkMusicActive(context: Context): Boolean {
        return runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isMusicActive ?: false
        }.getOrDefault(false)
    }
}
