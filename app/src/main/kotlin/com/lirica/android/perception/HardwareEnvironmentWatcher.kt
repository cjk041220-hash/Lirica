package com.lirica.android.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        val initialBattery = readBatterySnapshot(context)

        _hardwareState.update {
            it.copy(
                isCharging = initialCharging,
                isPhoneInCall = initialCall,
                isAudioPlaying = initialMusic,
                batteryTemperatureCelsius = initialBattery?.temperatureCelsius,
                batteryLevelPercent = initialBattery?.levelPercent,
                timestamp = System.currentTimeMillis()
            )
        }

        startLightSensor(context)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            // 音频/屏幕事件驱动通话与媒体状态动态刷新
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            // §7.3：电池温度与电量的唯一可靠来源就是这条系统粘性广播
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }

        val bReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null || ctx == null) return
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        val battery = readBatterySnapshot(ctx)
                        _hardwareState.update {
                            it.copy(
                                isCharging = true,
                                batteryTemperatureCelsius = battery?.temperatureCelsius ?: it.batteryTemperatureCelsius,
                                batteryLevelPercent = battery?.levelPercent ?: it.batteryLevelPercent,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        val battery = readBatterySnapshot(ctx)
                        _hardwareState.update {
                            it.copy(
                                isCharging = false,
                                batteryTemperatureCelsius = battery?.temperatureCelsius ?: it.batteryTemperatureCelsius,
                                batteryLevelPercent = battery?.levelPercent ?: it.batteryLevelPercent,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        // §7.3 热功耗熔断的数据源：温度与电量随系统广播刷新（系统本就按分钟级节流）
                        val battery = readBatterySnapshot(ctx)
                        if (battery != null) {
                            _hardwareState.update {
                                it.copy(
                                    batteryTemperatureCelsius = battery.temperatureCelsius,
                                    batteryLevelPercent = battery.levelPercent,
                                    timestamp = System.currentTimeMillis()
                                )
                            }
                        }
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
        stopLightSensor(context)
    }

    // =====================================================================
    // M5.2 §7.3：光照强度监听
    // =====================================================================

    private var lightSensorListener: SensorEventListener? = null

    /**
     * 注册环境光传感器。
     *
     * 代价权衡：SENSOR_DELAY_NORMAL（约 5Hz）在绝大多数设备上由低功耗协处理器承担，
     * 常驻开销可忽略；不注册 SENSOR_DELAY_UI/FASTEST——那才会真正吃电。
     * 无光传感器的设备静默跳过（不伪造默认值，语义上保持"未知"）。
     */
    private fun startLightSensor(context: Context) {
        if (lightSensorListener != null) return
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val lux = event?.values?.firstOrNull() ?: return
                _hardwareState.update { it.copy(ambientLightLux = lux) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        if (registered) {
            lightSensorListener = listener
        }
    }

    private fun stopLightSensor(context: Context) {
        val listener = lightSensorListener ?: return
        runCatching {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager?.unregisterListener(listener)
        }
        lightSensorListener = null
    }

    /**
     * 电池快照：温度与电量**各自独立可空**。
     *
     * 为什么不能用 Pair<Float, Int> 加默认值兜底：部分 ROM 只回传其中一项，
     * 用 0 补齐会让"温度未知"被当成"0℃ 很凉快"（漏熔断），
     * 或让"电量未知"被当成"0% 没电了"（误熔断视觉通道，用户莫名其妙被限流）。
     * 两个字段各自表达"未知"，判定方才能分别处理。
     */
    internal data class BatterySnapshot(
        val temperatureCelsius: Float?,
        val levelPercent: Int?
    )

    /**
     * 读取电池温度与电量。
     *
     * @return 快照；广播不可用时返回 null。缺项保持 null（诚实表达"未知"），
     *         绝不默认成 25℃ / 100%
     */
    private fun readBatterySnapshot(context: Context): BatterySnapshot? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temperature = if (tenths == Int.MIN_VALUE) null else tenths / 10f
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        if (temperature == null && percent == null) null
        else BatterySnapshot(temperature, percent)
    }.getOrNull()

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
