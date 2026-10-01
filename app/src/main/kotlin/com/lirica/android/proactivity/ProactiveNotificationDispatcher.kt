package com.lirica.android.proactivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

    /**
     * 系统层是否允许我们发通知。
     *
     * Android 13+ 未授予 POST_NOTIFICATIONS 时，[NotificationManager.notify] **不抛异常**、
     * 静默丢弃。如果这里不前置判定，调用方会拿到 true、日志写"已递送"、并进入 30 分钟冷却——
     * 即"日志说发了，手机上什么都没有"，且真实可送达的时机被冷却吃掉。
     * 因此授权判定必须发生在冷却置位之前。
     */
    fun canPostNotifications(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)

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
     *
     * 门控顺序（顺序即语义，不可调换）：
     * 1. 冷却门：距上次**成功**递送不足 [minCooldownMs] 直接阻断；
     * 2. 授权门：系统未放行通知权限时返回 false 且**不置冷却**（否则用户一旦授权，
     *    还要白等一个不应期才能收到第一条）；
     * 3. 真正 notify。
     *
     * @return true 表示已成功投递给系统通知栏；false 表示被冷却门/授权门拦截或派发异常
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

        if (!canPostNotifications(context)) {
            LiricaLog.w("NotificationDispatcher", "系统通知权限未放行，取消本次 L3 投递（不消耗冷却）")
            return false
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        val notification = buildNotification(context, title, message)

        return try {
            nm.notify(notificationId, notification)
            // 仅在真正投递成功后进入不应期：失败不该惩罚下一次投递
            lastDispatchedTimestampMs = now
            LiricaLog.i("NotificationDispatcher", "已递送 L3 主动通知: $title - $message")
            true
        } catch (e: Exception) {
            LiricaLog.e("NotificationDispatcher", "递送主动通知失败", e)
            false
        }
    }
}