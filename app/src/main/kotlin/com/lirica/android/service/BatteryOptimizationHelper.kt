package com.lirica.android.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.lirica.android.common.logging.LiricaLog

/**
 * 电池优化白名单与主流定制 ROM（MIUI/HyperOS, EMUI/HarmonyOS, ColorOS, OriginOS 等）
 * 后台自启动引导与权限跳转工具类。
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /**
     * 判断当前应用是否已加入系统电池优化忽略白名单
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            LiricaLog.w(TAG, "Failed to check isIgnoringBatteryOptimizations", e)
            false
        }
    }

    /**
     * 构建直接申请加入白名单的系统对话框 Intent
     * 注意：需声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限
     */
    @SuppressLint("BatteryLife")
    fun createRequestIgnoreBatteryOptimizationsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 构建国内各大厂商自启动与省电策略专用设置页 Intent 列表（按优先级降序）
     */
    fun createVendorBatteryOptimizationIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val pkg = context.packageName

        // 1. 小米 / HyperOS / MIUI
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        // 2. 华为 / 荣耀 / HarmonyOS / EMUI
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        // 3. OPPO / 一加 / ColorOS
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.oplus.battery",
                    "com.oplus.battery.PowerControlActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        // 4. vivo / iQOO / OriginOS / FuntouchOS
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        // 5. 魅族 / Flyme
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        // 6. 三星
        intents.add(
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        return intents
    }

    /**
     * 尝试引导用户跳转至电池优化/自启动设置页。
     * 策略：先匹配并尝试开启厂商专属页面，若不支持则优雅回退至原生电池优化列表或应用详情页。
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val packageManager = context.packageManager

        // 1. 尝试厂商专属页
        val vendorIntents = createVendorBatteryOptimizationIntents(context)
        for (intent in vendorIntents) {
            if (isIntentResolvable(packageManager, intent)) {
                try {
                    context.startActivity(intent)
                    LiricaLog.i(TAG, "Opened vendor battery optimization page: ${intent.component}")
                    return true
                } catch (e: Exception) {
                    LiricaLog.w(TAG, "Failed to start vendor battery activity ${intent.component}", e)
                }
            }
        }

        // 2. 回退到标准原生忽视电池优化页面
        try {
            val standardIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (isIntentResolvable(packageManager, standardIntent)) {
                context.startActivity(standardIntent)
                LiricaLog.i(TAG, "Opened standard ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                return true
            }
        } catch (e: Exception) {
            LiricaLog.w(TAG, "Failed to start ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS", e)
        }

        // 3. 最后回退至应用详情页面 (App Details)
        return try {
            val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(detailsIntent)
            LiricaLog.i(TAG, "Opened fallback ACTION_APPLICATION_DETAILS_SETTINGS")
            true
        } catch (e: Exception) {
            LiricaLog.e(TAG, "All battery optimization intents failed to open", e)
            false
        }
    }

    private fun isIntentResolvable(packageManager: PackageManager, intent: Intent): Boolean {
        return packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        ).isNotEmpty()
    }
}
