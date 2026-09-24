package com.xiaoxi.android.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import com.xiaoxi.android.perception.model.HardwareEnvironmentState
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
 */
class HardwareEnvironmentWatcher {

    private val _hardwareState = MutableStateFlow(HardwareEnvironmentState())
    val hardwareState: StateFlow<HardwareEnvironmentState> = _hardwareState.asStateFlow()

    private var receiver: BroadcastReceiver? = null

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
                }
            }
        }

        runCatching {
            context.registerReceiver(bReceiver, filter)
            receiver = bReceiver
        }
    }

    fun stop(context: Context) {
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
            receiver = null
        }
    }

    internal fun updateStateForTest(newState: HardwareEnvironmentState) {
        _hardwareState.value = newState
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
