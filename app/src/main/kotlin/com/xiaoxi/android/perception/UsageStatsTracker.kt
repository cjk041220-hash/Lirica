package com.xiaoxi.android.perception

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.xiaoxi.android.perception.model.ForegroundAppInfo
import com.xiaoxi.android.perception.model.ScreenState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台应用使用情况追踪与停留时间感知器
 * 具备低能耗息屏门控感知与无锁状态流分发
 */
class UsageStatsTracker {

    private val _foregroundApp = MutableStateFlow<ForegroundAppInfo?>(null)
    val foregroundApp: StateFlow<ForegroundAppInfo?> = _foregroundApp.asStateFlow()

    private var trackingJob: Job? = null

    val isTracking: Boolean
        get() = trackingJob?.isActive == true

    /**
     * 检查是否已授予 PACKAGE_USAGE_STATS 特殊权限
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 生成跳转至系统应用使用情况授权页的 Intent
     */
    fun createPermissionSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 查询当前真实前台活跃应用
     * 优先解析实时 UsageEvents (ACTIVITY_RESUMED)
     */
    fun getForegroundApp(
        context: Context,
        usageStatsManager: UsageStatsManager? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
        lookbackMs: Long = 60_000L
    ): ForegroundAppInfo? {
        val manager = usageStatsManager ?: (context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)
        ?: return null

        val beginTime = (currentTimeMs - lookbackMs).coerceAtLeast(0L)
        val events = try {
            manager.queryEvents(beginTime, currentTimeMs)
        } catch (_: Exception) {
            null
        } ?: return null

        val event = UsageEvents.Event()
        var latestEventTime = 0L
        var latestPackageName: String? = null
        var latestClassName: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                if (event.timeStamp >= latestEventTime) {
                    latestEventTime = event.timeStamp
                    latestPackageName = event.packageName
                    latestClassName = event.className
                }
            }
        }

        return if (!latestPackageName.isNullOrEmpty()) {
            ForegroundAppInfo(
                packageName = latestPackageName,
                className = latestClassName,
                timestamp = latestEventTime
            )
        } else {
            null
        }
    }

    /**
     * 启动低能耗轮询协程追踪
     * 当屏幕状态为 OFF 时自动暂停计算与采样
     */
    fun startTracking(
        scope: CoroutineScope,
        context: Context? = null,
        pollIntervalMs: Long = 2000L,
        screenStateFlow: StateFlow<ScreenState>? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        appProvider: (() -> ForegroundAppInfo?)? = null
    ) {
        stopTracking()

        trackingJob = scope.launch(dispatcher) {
            while (isActive) {
                delay(pollIntervalMs)

                // L0 门控：若屏幕息屏，休眠跳过，绝不在待机时浪费 CPU
                if (screenStateFlow?.value == ScreenState.OFF) {
                    continue
                }

                val current = appProvider?.invoke() ?: context?.let { getForegroundApp(it) }
                if (current != null) {
                    val prev = _foregroundApp.value
                    if (prev != null && prev.packageName == current.packageName) {
                        _foregroundApp.value = prev.copy(
                            className = current.className ?: prev.className,
                            timestamp = current.timestamp,
                            dwellDurationMs = prev.dwellDurationMs + pollIntervalMs
                        )
                    } else {
                        _foregroundApp.value = current.copy(dwellDurationMs = 0L)
                    }
                }
            }
        }
    }

    /**
     * 停止追踪并取消协程
     */
    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
    }
}
