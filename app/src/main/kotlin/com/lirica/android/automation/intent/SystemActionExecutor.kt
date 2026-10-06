package com.lirica.android.automation.intent

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import com.lirica.android.automation.policy.AutomationPermission
import java.util.TimeZone

/**
 * 系统服务直调动作执行器（主方案5.0 §4.1 P2-6 扩展）。
 *
 * 为什么与 Intent 通道分开：
 * 手电筒 / 音量 / 亮度 / 日程四个动作在 Android 上根本不走 Intent——
 * 它们分别是 CameraManager、AudioManager、Settings.System 与 ContentResolver 的
 * 进程内直调。硬塞进 createIntent() 会得到一个永远返回 null 的分支，
 * 后人读到就会以为"参数非法"。分开表达，但共享同一份安全目录与裁决入口。
 */
interface SystemActionExecutor {
    fun toggleFlashlight(enabled: Boolean): SystemActionResult
    fun adjustVolume(stream: VolumeStream, deltaSteps: Int): SystemActionResult
    fun adjustBrightness(percent: Int): SystemActionResult
    fun insertCalendarEvent(action: IntentAction.AddCalendarEvent): SystemActionResult
}

/** 系统动作落地的显式结果（与 DispatchResult 同构，避免异常穿透到编排层） */
sealed interface SystemActionResult {
    data class Success(val detail: String) : SystemActionResult
    data class NotSupported(val reason: String) : SystemActionResult
    data class PermissionMissing(val permission: AutomationPermission, val reason: String) : SystemActionResult
    data class Failed(val reason: String) : SystemActionResult
    data class ExecutionError(val throwable: Throwable) : SystemActionResult
}

/**
 * Android 真机实现。
 *
 * 关键事实依据（写在实现里，防止后人凭直觉加权限）：
 * 1. CameraManager.setTorchMode 自 API 23 起**无需 CAMERA 权限**（R1）；
 *    相机被其它应用占用时该方法抛 CameraAccessException，必须处理失败分支，
 *    而不是当作"权限不够"再往 Manifest 里加权限；
 * 2. Settings.System.putInt 写亮度需 WRITE_SETTINGS 特殊权限，
 *    判定入口是 Settings.System.canWrite(context)（不是 checkSelfPermission）；
 *    写亮度前需把 SCREEN_BRIGHTNESS_MODE 置为 MANUAL，否则自动亮度会立刻覆盖写入值；
 * 3. 日程插入必须带 CALENDAR_ID，取 IS_PRIMARY=1 的主日历；
 *    无主日历（极简 ROM / 未登录账户）时诚实返回 Failed，不伪造成功。
 */
class AndroidSystemActionExecutor(
    private val context: Context
) : SystemActionExecutor {

    override fun toggleFlashlight(enabled: Boolean): SystemActionResult = runCatching {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return SystemActionResult.NotSupported("设备无 CameraManager 服务")
        val cameraId = resolveTorchCameraId(cameraManager)
            ?: return SystemActionResult.NotSupported("设备无可用闪光灯单元")
        cameraManager.setTorchMode(cameraId, enabled)
        SystemActionResult.Success(if (enabled) "手电筒已点亮" else "手电筒已熄灭")
    }.getOrElse { throwable ->
        // 相机被占用 / 硬件拒绝：诚实上报，绝不静默吞掉
        SystemActionResult.Failed(
            "手电筒切换失败（相机可能被占用）: " + (throwable.message ?: throwable::class.simpleName)
        )
    }

    private fun resolveTorchCameraId(cameraManager: CameraManager): String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val isBack = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            hasFlash && isBack
        } ?: cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    override fun adjustVolume(stream: VolumeStream, deltaSteps: Int): SystemActionResult {
        if (deltaSteps == 0) return SystemActionResult.Failed("音量调整步长为 0，无实际动作")
        if (deltaSteps > MAX_VOLUME_STEPS || deltaSteps < -MAX_VOLUME_STEPS) {
            return SystemActionResult.Failed("音量调整步长越界（单次上限 " + MAX_VOLUME_STEPS + " 档）")
        }
        return runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return SystemActionResult.NotSupported("设备无 AudioManager 服务")
            val direction = if (deltaSteps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            var applied = 0
            // 逐档调整：ADJUST_RAISE 不支持跨档跳跃，多档需循环
            repeat(kotlin.math.abs(deltaSteps)) {
                audioManager.adjustStreamVolume(stream.androidStream, direction, AudioManager.FLAG_SHOW_UI)
                applied++
            }
            val current = audioManager.getStreamVolume(stream.androidStream)
            val max = audioManager.getStreamMaxVolume(stream.androidStream)
            SystemActionResult.Success(
                "音量已调 " + (if (deltaSteps > 0) "+" else "-") + applied +
                    " 档，当前 " + current + "/" + max
            )
        }.getOrElse { SystemActionResult.ExecutionError(it) }
    }

    override fun adjustBrightness(percent: Int): SystemActionResult {
        if (percent !in 0..100) {
            return SystemActionResult.Failed("亮度百分比越界（合法区间 0~100，收到 " + percent + "）")
        }
        if (!Settings.System.canWrite(context)) {
            return SystemActionResult.PermissionMissing(
                permission = AutomationPermission.WRITE_SETTINGS,
                reason = "写系统设置需 WRITE_SETTINGS 特殊权限，需用户到系统设置页手动授权"
            )
        }
        return runCatching {
            val contentResolver = context.contentResolver
            // 先关自动亮度：否则系统会在下一次环境光采样时立即覆盖写入值
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            // 亮度原始量纲为 1~255；0 会导致息屏，故下限抬到 1
            val raw = (percent.coerceIn(1, 100) / 100f * MAX_BRIGHTNESS_RAW).toInt()
                .coerceIn(1, MAX_BRIGHTNESS_RAW)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
            SystemActionResult.Success("屏幕亮度已设为 " + percent + "%")
        }.getOrElse { SystemActionResult.ExecutionError(it) }
    }

    override fun insertCalendarEvent(action: IntentAction.AddCalendarEvent): SystemActionResult {
        if (action.title.isBlank()) return SystemActionResult.Failed("日程标题为空，拒绝写入")
        if (action.endTimeMs <= action.beginTimeMs) {
            return SystemActionResult.Failed("日程结束时间必须晚于开始时间")
        }
        if (!hasCalendarPermission()) {
            return SystemActionResult.PermissionMissing(
                permission = AutomationPermission.WRITE_CALENDAR,
                reason = "写入日程需 WRITE_CALENDAR 运行时权限"
            )
        }
        return runCatching {
            val calendarId = queryPrimaryCalendarId()
                ?: return SystemActionResult.Failed("设备上没有可写主日历（可能未登录账号或 ROM 精简）")

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, action.title)
                put(CalendarContract.Events.DTSTART, action.beginTimeMs)
                put(CalendarContract.Events.DTEND, action.endTimeMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                action.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                action.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return SystemActionResult.Failed("日程插入返回空 URI")
            SystemActionResult.Success("日程已写入: " + action.title + " (" + uri.lastPathSegment + ")")
        }.getOrElse { SystemActionResult.ExecutionError(it) }
    }

    private fun hasCalendarPermission(): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun queryPrimaryCalendarId(): Long? = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            CalendarContract.Calendars.IS_PRIMARY + " = 1",
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }.getOrElse { throwable ->
        Log.w("LiricaSystemAction", "主日历查询失败", throwable)
        null
    }

    companion object {
        /** 单次音量调整档数上限（防"一键静音"式的越权音量操作） */
        const val MAX_VOLUME_STEPS = 15

        /** Android 亮度原始量纲上限 */
        const val MAX_BRIGHTNESS_RAW = 255
    }
}
