package com.lirica.android.common.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.lirica.android.automation.policy.AutomationPermission
import com.lirica.android.automation.policy.AutomationPermissionProvider
import com.lirica.android.perception.UsageStatsTracker
import com.lirica.android.perception.LiricaAccessibilityService
import com.lirica.android.service.BatteryOptimizationHelper
import rikka.shizuku.Shizuku

enum class PermissionType(
    val title: String,
    val description: String,
    val isCrucial: Boolean
) {
    ACCESSIBILITY(
        title = "无障碍服务",
        description = "用于读取屏幕界面结构与模拟自动化代行操作",
        isCrucial = true
    ),
    OVERLAY(
        title = "悬浮窗权限",
        description = "用于显示伴生体悬浮球、破冰气泡与交互卡片",
        isCrucial = true
    ),
    NOTIFICATION_LISTENER(
        title = "通知使用权",
        description = "用于感知通知事件，提供智能关怀与消息提醒",
        isCrucial = false
    ),
    USAGE_STATS(
        title = "应用使用情况",
        description = "用于感知当前前台应用与屏幕切换，实现金融场景保护",
        isCrucial = false
    ),
    BATTERY_OPTIMIZATION(
        title = "忽略电池优化",
        description = "防止系统息屏或切后台时杀掉 Lirica 伴生服务",
        isCrucial = true
    ),
    SHIZUKU(
        title = "Shizuku ADB 提权",
        description = "可选高级特权，用于毫秒级极速手势穿透与物理按键代行",
        isCrucial = false
    ),

    /**
     * M5.1 新增（复审 R6）：写入日程需 WRITE_CALENDAR 运行时权限。
     *
     * 注意与手电筒的区别：CameraManager.setTorchMode **不需要** CAMERA 权限（R1），
     * 因此本枚举中刻意没有 CAMERA 项——照抄旧文档会引入一个多余的危险权限弹窗。
     */
    CALENDAR(
        title = "日历读写",
        description = "用于把日程与约定写进系统日历（原生 API 动作通道）",
        isCrucial = false
    ),

    /**
     * M5.1 新增（复审 R6）：调整屏幕亮度需 WRITE_SETTINGS 特殊权限。
     *
     * 特殊权限无法用 requestPermissions 弹窗授予，只能引导到系统设置页手动打开，
     * 故 mustGoToSettingsPage 语义在 [createIntentForPermission] 中体现。
     */
    WRITE_SETTINGS(
        title = "修改系统设置",
        description = "用于代调屏幕亮度（系统特殊权限，需在设置页手动授权）",
        isCrucial = false
    )
}

data class PermissionItemState(
    val type: PermissionType,
    val isGranted: Boolean,
    val details: String? = null
)

object PermissionManager {

    fun checkPermission(context: Context, type: PermissionType): Boolean {
        return runCatching {
            when (type) {
                PermissionType.ACCESSIBILITY -> {
                    LiricaAccessibilityService.isAccessibilityServiceEnabled(context)
                }
                PermissionType.OVERLAY -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Settings.canDrawOverlays(context)
                    } else {
                        true
                    }
                }
                PermissionType.NOTIFICATION_LISTENER -> {
                    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                }
                PermissionType.USAGE_STATS -> {
                    UsageStatsTracker.hasPermission(context)
                }
                PermissionType.BATTERY_OPTIMIZATION -> {
                    BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                }
                PermissionType.SHIZUKU -> {
                    val isBinderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                    val isPermitted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                    isBinderAlive && isPermitted
                }
                PermissionType.CALENDAR -> {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_CALENDAR
                    ) == PackageManager.PERMISSION_GRANTED
                }
                PermissionType.WRITE_SETTINGS -> {
                    // 特殊权限的唯一权威判定入口是 Settings.System.canWrite，
                    // 用 checkSelfPermission 判它恒为 false（这是常见实现陷阱）
                    Settings.System.canWrite(context)
                }
            }
        }.getOrDefault(false)
    }

    fun checkAll(context: Context): List<PermissionItemState> {
        return PermissionType.values().map { type ->
            val isGranted = checkPermission(context, type)
            val details = when {
                isGranted -> "已授权生效"
                type.isCrucial -> "未开启（核心伴生功能受限）"
                else -> "未开启（可选功能）"
            }
            PermissionItemState(
                type = type,
                isGranted = isGranted,
                details = details
            )
        }
    }

    fun createIntentForPermission(context: Context, type: PermissionType): Intent {
        return when (type) {
            PermissionType.ACCESSIBILITY -> {
                LiricaAccessibilityService.createSettingsIntent()
            }
            PermissionType.OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }
            PermissionType.NOTIFICATION_LISTENER -> {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            PermissionType.USAGE_STATS -> {
                UsageStatsTracker.createPermissionSettingsIntent(context)
            }
            PermissionType.BATTERY_OPTIMIZATION -> {
                BatteryOptimizationHelper.createRequestIgnoreBatteryOptimizationsIntent(context.packageName)
            }
            PermissionType.SHIZUKU -> {
                val shizukuPkg = "moe.shizuku.privileged.api"
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(shizukuPkg)
                launchIntent ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            PermissionType.CALENDAR -> {
                // 运行时权限无系统设置页可跳；退化为应用详情页（用户可在其中手动放行），
                // 主路径仍走 Onboarding/Settings 的 requestPermissions 弹窗
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            PermissionType.WRITE_SETTINGS -> {
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }

    /**
     * 把权限体检结果适配成安全裁决引擎所需的 [AutomationPermissionProvider]。
     *
     * 之所以要有这层适配：裁决引擎必须能在纯 JVM 单测里被穷举，
     * 不能直接依赖 Context；而真机上"权限是否已授予"又只能由系统 API 回答。
     * 适配层是这两者之间唯一的接缝。
     */
    fun automationPermissionProvider(context: Context): AutomationPermissionProvider =
        AutomationPermissionProvider { permission ->
            when (permission) {
                AutomationPermission.WRITE_CALENDAR -> checkPermission(context, PermissionType.CALENDAR)
                AutomationPermission.WRITE_SETTINGS -> checkPermission(context, PermissionType.WRITE_SETTINGS)
            }
        }
}
