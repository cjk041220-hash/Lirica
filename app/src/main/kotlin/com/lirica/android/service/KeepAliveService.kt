package com.lirica.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.lirica.android.MainActivity
import com.lirica.android.overlay.CompanionOverlayService

/**
 * 前台保活与状态同步服务 (KeepAliveService)
 *
 * 遵循 Android 14+ specialUse 前台服务规范，维持轻量低能耗常驻守护。
 * 支持开机自启、通知栏常驻展示与随地唤醒伴生悬浮窗。
 */
class KeepAliveService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KeepAliveService onCreate")
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(TAG, "KeepAliveService onStartCommand, action: $action")

        if (action == ACTION_STOP_SERVICE) {
            Log.i(TAG, "Stopping KeepAliveService foreground")
            // 同步停止伴生悬浮窗服务：否则用户点"停止伴生"后悬浮球残留屏幕，
            // 且进程被系统回收后 START_STICKY 复活时悬浮窗可能随机重现
            try {
                stopService(Intent(this, CompanionOverlayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop CompanionOverlayService", e)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 启动前台常驻通知
        startForegroundInternal()

        // 若用户已授权悬浮窗，自适应拉起伴生悬浮窗服务
        tryLaunchOverlayService()

        return START_STICKY
    }

    private fun startForegroundInternal() {
        val notification = buildNotification(this)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            Log.i(TAG, "KeepAliveService started foreground successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground on KeepAliveService", e)
        }
    }

    private fun tryLaunchOverlayService() {
        try {
            if (Settings.canDrawOverlays(this)) {
                val overlayIntent = Intent(this, CompanionOverlayService::class.java)
                startService(overlayIntent)
                Log.i(TAG, "CompanionOverlayService auto-launched from KeepAliveService")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to auto-launch CompanionOverlayService", e)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "KeepAliveService onDestroy")
    }

    companion object {
        private const val TAG = "KeepAliveService"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "lirica_keepalive_channel"
        const val CHANNEL_NAME = "Lirica 伴生保活服务"

        const val ACTION_STOP_SERVICE = "com.lirica.android.action.STOP_KEEPALIVE"
        const val ACTION_START_OVERLAY = "com.lirica.android.action.START_OVERLAY"

        /**
         * 启动伴生保活服务（Android 8.0+ 自动提权为前台服务）
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "KeepAliveService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start KeepAliveService", e)
            }
        }

        /**
         * 停止伴生保活服务与常驻悬浮窗
         */
        fun stop(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
                Log.i(TAG, "KeepAliveService stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop KeepAliveService", e)
            }
        }

        /**
         * 创建 Android 8.0+ 前台通知渠道
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (notificationManager != null && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Lirica 桌面伴生与轻量低耗常驻心智通道"
                        setShowBadge(false)
                    }
                    notificationManager.createNotificationChannel(channel)
                }
            }
        }

        /**
         * 构建标准低能耗常驻通知
         */
        fun buildNotification(context: Context): Notification {
            createNotificationChannel(context)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingOpenApp = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopServiceIntent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val pendingStopService = PendingIntent.getService(
                context,
                1,
                stopServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Lirica 伴生中")
                .setContentText("正在守护与感知主人动态...")
                .setContentIntent(pendingOpenApp)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止伴生",
                    pendingStopService
                )
                .build()
        }
    }
}
