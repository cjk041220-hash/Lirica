package com.xiaoxi.android.perception

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoxi.android.XiaoxiApplication
import com.xiaoxi.android.perception.model.NotificationCategory
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = XiaoxiApplication::class, sdk = [34])
class NotificationFilterTest {

    private lateinit var filter: NotificationFilter

    @Before
    fun setUp() {
        filter = NotificationFilter()
    }

    private fun createMockSbn(
        packageName: String,
        title: String?,
        text: String?,
        isOngoing: Boolean = false,
        progress: Int = 0,
        maxProgress: Int = 0,
        isIndeterminate: Boolean = false,
        postTime: Long = 1000L,
        id: Int = 101,
        tag: String? = null
    ): StatusBarNotification {
        val sbn = mockk<StatusBarNotification>()
        val extras = Bundle().apply {
            title?.let { putCharSequence(Notification.EXTRA_TITLE, it) }
            text?.let { putCharSequence(Notification.EXTRA_TEXT, it) }
            if (maxProgress > 0 || isIndeterminate) {
                putInt(Notification.EXTRA_PROGRESS, progress)
                putInt(Notification.EXTRA_PROGRESS_MAX, maxProgress)
                putBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, isIndeterminate)
            }
        }
        val notification = Notification().apply {
            this.extras = extras
        }

        every { sbn.packageName } returns packageName
        every { sbn.postTime } returns postTime
        every { sbn.id } returns id
        every { sbn.tag } returns tag
        every { sbn.isOngoing } returns isOngoing
        every { sbn.isClearable } returns !isOngoing
        every { sbn.notification } returns notification

        return sbn
    }

    @Test
    fun testFilterOngoingNotification_isDropped() {
        val sbn = createMockSbn(
            packageName = "com.netease.cloudmusic",
            title = "正在播放",
            text = "林俊杰 - 江南",
            isOngoing = true
        )
        assertNull(filter.filterAndExtract(sbn))
    }

    @Test
    fun testFilterProgressBarNotification_isDropped() {
        val sbn = createMockSbn(
            packageName = "com.android.providers.downloads",
            title = "下载中...",
            text = "update.apk",
            progress = 45,
            maxProgress = 100
        )
        assertNull(filter.filterAndExtract(sbn))
    }

    @Test
    fun testFilterIndeterminateProgress_isDropped() {
        val sbn = createMockSbn(
            packageName = "com.download.manager",
            title = "正在准备下载",
            text = "请稍候...",
            isIndeterminate = true
        )
        assertNull(filter.filterAndExtract(sbn))
    }

    @Test
    fun testFilterEmptyNotification_isDropped() {
        val sbn = createMockSbn(
            packageName = "com.example.app",
            title = "",
            text = null
        )
        assertNull(filter.filterAndExtract(sbn))
    }

    @Test
    fun testFilterCommunicationApp_isAccepted() {
        val sbn = createMockSbn(
            packageName = "com.tencent.mm",
            title = "张三",
            text = "今晚一起吃饭吗？"
        )
        val event = filter.filterAndExtract(sbn)
        assertNotNull(event)
        assertEquals("com.tencent.mm", event?.packageName)
        assertEquals("张三", event?.title)
        assertEquals("今晚一起吃饭吗？", event?.content)
        assertEquals(NotificationCategory.COMMUNICATION, event?.category)
    }

    @Test
    fun testFilterTravelLogistics_isAccepted() {
        val sbn = createMockSbn(
            packageName = "com.sankuai.meituan",
            title = "美团外卖",
            text = "您的骑手已接单，预计 12:30 送达"
        )
        val event = filter.filterAndExtract(sbn)
        assertNotNull(event)
        assertEquals(NotificationCategory.TRAVEL_LOGISTICS, event?.category)
        assertTrue(event?.content?.contains("送达") == true)
    }

    @Test
    fun testFilterScheduleAndReminders_isAccepted() {
        val sbn = createMockSbn(
            packageName = "com.android.calendar",
            title = "日程提醒",
            text = "15:00 团队项目周会"
        )
        val event = filter.filterAndExtract(sbn)
        assertNotNull(event)
        assertEquals(NotificationCategory.SCHEDULE, event?.category)
    }

    @Test
    fun testFilterSecurityOtp_isDropped() {
        val sbn = createMockSbn(
            packageName = "com.android.mms",
            title = "95588",
            text = "【工商银行】您的验证码为 829103，切勿泄露给他人。"
        )
        assertNull(filter.filterAndExtract(sbn))
    }

    @Test
    fun testFilterEnglishOtp_isDropped() {
        val sbn = createMockSbn(
            packageName = "com.google.android.gms",
            title = "Google",
            text = "Your verification code is 482913. Do not share it."
        )
        assertNull(filter.filterAndExtract(sbn))
    }

    @Test
    fun testTextTruncation_limitsMaxLengthTo256() {
        val longText = "A".repeat(500)
        val sbn = createMockSbn(
            packageName = "com.tencent.mobileqq",
            title = "群消息",
            text = longText
        )
        val event = filter.filterAndExtract(sbn)
        assertNotNull(event)
        assertEquals(256, event?.content?.length)
    }
}
