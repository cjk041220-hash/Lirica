package com.xiaoxi.android.proactivity

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * 精确闹钟权限门控测试（P0-3）
 *
 * Android 12+ (targetSdk 31+) 精确闹钟受 SCHEDULE_EXACT_ALARM 门控，
 * 用户撤销授权时 AlarmManager.setExactAndAllowWhileIdle 会抛 SecurityException，
 * 击穿 schedule_wake 工具调用链。本组用例验证：
 * 1. 无权限时自动降级 setWindow（不抛异常，闹钟仍注册）
 * 2. 有权限时走精确闹钟路径（窗口长度为 0）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AndroidSystemAlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: AndroidSystemAlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduler = AndroidSystemAlarmScheduler(context)
    }

    @Test
    fun testScheduleAlarm_fallsBackToSetWindow_whenExactAlarmNotPermitted() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        scheduler.scheduleAlarm("alarm_no_perm", 1726800000000L)

        // 降级路径：闹钟必须注册成功（旧实现此处直接抛 SecurityException）
        val scheduled = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull("无精确闹钟权限时闹钟必须以 setWindow 方式注册", scheduled)
        assertEquals(
            "降级闹钟必须使用 setWindow 窗口（10 分钟）",
            600_000L,
            scheduled.windowLengthMs
        )
        assertEquals(
            "降级闹钟触发时间起点不得被推迟",
            1726800000000L,
            scheduled.triggerAtMs
        )
    }

    @Test
    fun testScheduleAlarm_usesExactPath_whenPermitted() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        scheduler.scheduleAlarm("alarm_perm", 1726800000000L)

        val scheduled = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull("有权限时闹钟必须注册", scheduled)
        assertEquals("有权限时必须走精确闹钟路径（无窗口）", 0L, scheduled.windowLengthMs)
        assertEquals(1726800000000L, scheduled.triggerAtMs)
    }

    @Test
    fun testCancelAlarm_removesScheduledAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        scheduler.scheduleAlarm("alarm_cancel", 1726800000000L)
        scheduler.cancelAlarm("alarm_cancel")

        assertEquals(
            "取消后不应残留已注册闹钟",
            null,
            shadowOf(alarmManager).nextScheduledAlarm
        )
    }
}
