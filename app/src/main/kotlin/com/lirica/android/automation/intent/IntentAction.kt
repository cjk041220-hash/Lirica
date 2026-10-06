package com.lirica.android.automation.intent

import android.media.AudioManager

enum class SettingType {
    WIFI,
    BLUETOOTH,
    ACCESSIBILITY,
    APP_DETAILS,
    GENERAL
}

/**
 * 音量流类型（主方案5.0 §4.1 音量动作）。
 *
 * 只暴露四类用户可感知的流，刻意不开放 STREAM_VOICE_CALL / STREAM_SYSTEM：
 * 前者会干扰通话，后者属系统提示音，均不适合由伴生体代调。
 */
enum class VolumeStream(val androidStream: Int) {
    MEDIA(AudioManager.STREAM_MUSIC),
    RING(AudioManager.STREAM_RING),
    ALARM(AudioManager.STREAM_ALARM),
    NOTIFICATION(AudioManager.STREAM_NOTIFICATION)
}

/**
 * 系统级动作契约（主方案5.0 §4.1 / §二"协议扩展"裁决）。
 *
 * 设计裁决（对照复审 R1/R2/R3）：
 * 1. 沿用既有 sealed interface 扩展点，不另起 SystemAction 平行体系，
 *    否则会重现"平行实现 + 某条通道漏调闸门"的双轨风险；
 * 2. 本接口同时容纳「Intent 拉起型」与「系统服务直调型」动作：
 *    前者由 [IntentActionDispatcher.createIntent] 生成 Intent，
 *    后者（手电筒/音量/亮度/日程）由 [SystemActionExecutor] 落地——
 *    但它们共享同一份安全目录与同一条裁决入口；
 * 3. 新增动作一律先在 SystemActionCatalog 登记三元组，再在此处加分支，
 *    顺序反了会导致 fail-closed 拒绝（这是刻意的）。
 */
sealed interface IntentAction {
    data class Dial(val phoneNumber: String) : IntentAction

    /**
     * 短信预填草稿（ACTION_SENDTO smsto:）。
     *
     * 复审 R2 事实修正：本动作**不需要 SEND_SMS 权限**，也不会自动发送——
     * 它只打开短信应用并预填正文，最终由用户按下发送键。
     */
    data class SendSms(val phoneNumber: String, val message: String) : IntentAction

    /** 邮件预填草稿（ACTION_SENDTO mailto:），与短信同策略（R3） */
    data class SendEmail(
        val recipient: String,
        val subject: String? = null,
        val body: String? = null
    ) : IntentAction

    data class SetAlarm(
        val hour: Int,
        val minutes: Int,
        val label: String = "",
        val skipUi: Boolean = false
    ) : IntentAction

    /**
     * 写入日程（ContentResolver 插入 CalendarContract.Events）。
     *
     * 需 WRITE_CALENDAR 运行时权限；缺失时裁决层返回引导而非静默失败（R6）。
     */
    data class AddCalendarEvent(
        val title: String,
        val beginTimeMs: Long,
        val endTimeMs: Long,
        val description: String? = null,
        val location: String? = null
    ) : IntentAction {
        val durationMs: Long get() = (endTimeMs - beginTimeMs).coerceAtLeast(0L)
    }

    data class OpenMap(
        val query: String,
        val latitude: Double? = null,
        val longitude: Double? = null
    ) : IntentAction

    data class OpenSettings(
        val settingType: SettingType,
        val packageName: String? = null
    ) : IntentAction

    data class LaunchApp(
        val packageName: String,
        val fallbackWebUri: String? = null
    ) : IntentAction

    data class ShareText(
        val text: String,
        val targetPackage: String? = null
    ) : IntentAction

    /**
     * 手电筒开关（CameraManager.setTorchMode）。
     *
     * 复审 R1 事实修正：**无需 CAMERA 权限**（API 23+）。此处加 CAMERA 会触发
     * 运行时弹窗、被系统判定为占用相机，并污染权限体检项。
     */
    data class ToggleFlashlight(val enabled: Boolean) : IntentAction

    /** 音量调整（AudioManager.adjustStreamVolume），deltaSteps 正加负减 */
    data class AdjustVolume(
        val stream: VolumeStream = VolumeStream.MEDIA,
        val deltaSteps: Int = 1
    ) : IntentAction

    /**
     * 屏幕亮度调整（Settings.System.SCREEN_BRIGHTNESS，0~100 百分比）。
     *
     * 需 WRITE_SETTINGS 特殊权限：缺失时裁决层返回系统设置页引导。
     */
    data class AdjustBrightness(val percent: Int) : IntentAction
}
