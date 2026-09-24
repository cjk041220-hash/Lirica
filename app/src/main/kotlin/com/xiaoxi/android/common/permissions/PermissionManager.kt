package com.xiaoxi.android.common.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.xiaoxi.android.perception.UsageStatsTracker
import com.xiaoxi.android.perception.XiaoxiAccessibilityService
import com.xiaoxi.android.service.BatteryOptimizationHelper
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
                    XiaoxiAccessibilityService.isAccessibilityServiceEnabled(context)
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
                XiaoxiAccessibilityService.createSettingsIntent()
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
        }
    }
}
