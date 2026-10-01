package com.lirica.android.proactivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lirica.android.MainActivity
import com.lirica.android.common.logging.LiricaLog

/**
 * L3 级系统主动通知调度器 (ProactiveNotificationDispatcher - M4.4 核心)
 *
 * 核心职能：
 * 1. 负责管理 Lirica 专属主动温暖关怀与开环提醒系统通知通道；
 * 2. 在用户处于息屏、未授权悬浮窗或重要未决事项（Open Loops）到期时，以标准系统通知栏形态温和送达；
 * 3. 严格内置 30 分钟不应期冷却与频次上限，彻底消灭突兀打扰与推送轰炸。
 */
class ProactiveNotificationDispatcher(
    val minCooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val CHANNEL_ID = "lirica_proactive_care_channel"
        const val CHANNEL_NAME = "Lirica 温暖关怀与事项提醒"
        const val CHANNEL_DESC = "用于接收来自伴生伙伴的偶遇式关心、未决事项跟进与生活提醒"

        const val DEFAULT_COOLDOWN_MS = 30 * 60 * 1000L // 同类主动通知 30 分钟不应期冷却
        const val DEFAULT_NOTIFICATION_ID = 2001

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = CHANNEL_DESC
                        enableVibration(true)
                        setShowBadge(true)
                    }
                    nm.createNotificationChannel(channel)
                    LiricaLog.i("NotificationDispatcher", "L3 温暖关怀通知通道注册成功")
                }
            }
        }
    }

    var lastDispatchedTimestampMs: Long = 0L
        private set

    fun canDispatch(nowMs: Long = timeProvider()): Boolean {
        if (lastDispatchedTimestampMs == 0L) return true
        return (nowMs - lastDispatchedTimestampMs) >= minCooldownMs
    }

    fun buildNotification(context: Context, title: String, message: String): Notification {
        createNotificationChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    /** 
     * 派发主动关怀通知
     * @return true 成功递送并展示；false 被冷却门拦截或无权限
     */
    fun dispatchNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
        forceBypassCooldown: Boolean = false
    ): Boolean {
        val now = timeProvider()
        if (!forceBypassCooldown && !canDispatch(now)) {
            LiricaLog.d("NotificationDispatcher", "主动关怀通知处于冷却期内，静默阻断")
            return false
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        // 无论系统层是否授权，只要尝试过派发即刻进入不应期冷却，杜绝无权限时的死循环重试
        lastDispatchedTimestampMs = now

        val notification = buildNotification(context, title, message)

        return try {
            nm.notify(notificationId, notification)
            LiricaLog.i("NotificationDispatcher", "已递送 L3 主动通知: $title - $message")
            true
        } catch (e: Exception) {
            LiricaLog.e("NotificationDispatcher", "递送主动通知失败", e)
            false
        }
    }
}