package com.lirica.android.proactivity

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProactiveNotificationDispatcherTest {

    private lateinit var context: Context
    private var currentTime = 1000000L
    private lateinit var dispatcher: ProactiveNotificationDispatcher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        currentTime = 1000000L
        dispatcher = ProactiveNotificationDispatcher(
            minCooldownMs = 1800000L, // 30 分钟
            timeProvider = { currentTime }
        )
    }

    @Test
    fun testNotificationChannelCreatedCorrectly() {
        ProactiveNotificationDispatcher.createNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(ProactiveNotificationDispatcher.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(ProactiveNotificationDispatcher.CHANNEL_NAME, channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun testBuildNotification_hasCorrectAttributes() {
        val notification = dispatcher.buildNotification(
            context = context,
            title = "小汐的关怀",
            message = "今天在自习室学得很认真呀，记得起来走走哦。"
        )
        assertNotNull(notification)
        assertEquals(ProactiveNotificationDispatcher.CHANNEL_ID, notification.channelId)
        assertNotNull(notification.contentIntent)
        val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        assertEquals("小汐的关怀", title)
    }

    @Test
    fun testCooldownGate_blocksFrequentDispatches() {
        // 初始状态允许派发
        assertTrue(dispatcher.canDispatch(currentTime))

        // 尝试首次派发
        dispatcher.dispatchNotification(context, "提醒1", "消息1")
        assertEquals(currentTime, dispatcher.lastDispatchedTimestampMs)

        // 10 分钟后（未满 30 分钟），应被冷却门阻断
        currentTime += 600000L
        assertFalse(dispatcher.canDispatch(currentTime))
        val blocked = dispatcher.dispatchNotification(context, "提醒2", "消息2")
        assertFalse("冷却期内应返回 false", blocked)

        // 35 分钟后（超过 30 分钟），解除冷却
        currentTime += 1500000L
        assertTrue(dispatcher.canDispatch(currentTime))
    }
}