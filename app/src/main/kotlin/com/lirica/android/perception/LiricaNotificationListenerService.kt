package com.lirica.android.perception

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lirica.android.perception.model.NotificationEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局通知捕获与广播监听服务
 * 结合 NotificationFilter 实施低能耗过滤与结构化事件分发
 */
class LiricaNotificationListenerService : NotificationListenerService() {

    private val notificationFilter = NotificationFilter()

    companion object {
        @Volatile
        var isServiceConnected: Boolean = false
            private set

        private val _latestNotification = MutableStateFlow<NotificationEvent?>(null)
        val latestNotification: StateFlow<NotificationEvent?> = _latestNotification.asStateFlow()

        private val _notificationEvents = MutableSharedFlow<NotificationEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val notificationEvents: SharedFlow<NotificationEvent> = _notificationEvents.asSharedFlow()

        /**
         * 检查用户是否已在系统设置中启用本应用的通知使用权
         */
        fun isNotificationListenerEnabled(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            val myComponent = ComponentName(context, LiricaNotificationListenerService::class.java).flattenToString()
            val myFlatComponent = ComponentName(context, LiricaNotificationListenerService::class.java).flattenToShortString()
            return enabledListeners.contains(myComponent) || enabledListeners.contains(myFlatComponent)
        }

        /**
         * 生成跳转至系统通知监听设置页的 Intent
         */
        fun createSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val event = notificationFilter.filterAndExtract(sbn) ?: return
            _latestNotification.value = event
            _notificationEvents.tryEmit(event)
        } catch (_: Exception) {
            // 严防外部异常导致服务崩溃
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知划掉/清除处理 (如需同步未读状态可在此扩展)
    }
}
