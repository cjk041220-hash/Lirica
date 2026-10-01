package com.lirica.android.proactivity

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 派发器授权门断言。
 *
 * 真实缺陷场景：Android 13+ 未授予 POST_NOTIFICATIONS 时 NotificationManager.notify() 不抛异常、
 * 静默丢弃；旧实现会照样返回 true、写"已递送"日志、并置上 30 分钟冷却——
 * 于是"日志说发了、手机上什么都没有"，而且用户授权后还要白等一个不应期。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProactiveNotificationDispatcherGateTest {

    private lateinit var context: Context
    private var now = 1_000_000L
    private lateinit var dispatcher: ProactiveNotificationDispatcher

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        now = 1_000_000L
        dispatcher = ProactiveNotificationDispatcher(minCooldownMs = 30 * 60 * 1000L, timeProvider = { now })
    }

    @Test
    fun testWhenNotificationsDisabled_returnsFalseAndDoesNotConsumeCooldown() {
        notificationManager().let { Shadows.shadowOf(it).setNotificationsEnabled(false) }

        val delivered = dispatcher.dispatchNotification(context, "标题", "正文")

        assertFalse("无权限时必须报告未送达", delivered)
        assertEquals("未送达不应消耗冷却窗口", 0L, dispatcher.lastDispatchedTimestampMs)
        // 授权后立刻就能发第一条，不需要等不应期
        assertTrue(dispatcher.canDispatch(now))
    }

    @Test
    fun testWhenNotificationsEnabled_deliversAndStartsCooldown() {
        notificationManager().let { Shadows.shadowOf(it).setNotificationsEnabled(true) }

        val delivered = dispatcher.dispatchNotification(context, "标题", "正文")

        assertTrue(delivered)
        assertEquals(now, dispatcher.lastDispatchedTimestampMs)
        assertEquals(1, Shadows.shadowOf(notificationManager()).size())
    }

    @Test
    fun testCooldownBlocksSecondDeliveryWithinWindow() {
        notificationManager().let { Shadows.shadowOf(it).setNotificationsEnabled(true) }

        assertTrue(dispatcher.dispatchNotification(context, "第一次", "正文1"))
        now += 29 * 60 * 1000L
        assertFalse("不应期内必须阻断", dispatcher.dispatchNotification(context, "第二次", "正文2"))
        now += 2 * 60 * 1000L
        assertTrue("超过 30 分钟后应恢复", dispatcher.canDispatch(now))
    }
}
