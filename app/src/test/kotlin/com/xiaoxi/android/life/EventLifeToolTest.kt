package com.xiaoxi.android.life

import com.xiaoxi.android.database.dao.ScheduledAlarmDao
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity
import com.xiaoxi.android.proactivity.ScheduleWakeManager
import com.xiaoxi.android.proactivity.SystemAlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

class EventLifeToolTest {

    private val fakeNow = 1_758_500_000_000L
    private val zone = ZoneId.of("Asia/Shanghai")

    private lateinit var scheduledAlarmDao: ScheduledAlarmDao
    private lateinit var alarmScheduler: SystemAlarmScheduler
    private lateinit var tool: EventLifeTool

    @Before
    fun setUp() {
        scheduledAlarmDao = mockk(relaxed = true)
        val unspokenThoughtDao = mockk<UnspokenThoughtDao>(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        coEvery { scheduledAlarmDao.insert(any()) } returns 1L

        // 用真实 ScheduleWakeManager 而非 mock 具体类：create_event 的核心价值
        // 就是"落库即注册系统闹钟"这条链路本身
        val manager = ScheduleWakeManager(
            scheduledAlarmDao = scheduledAlarmDao,
            unspokenThoughtDao = unspokenThoughtDao,
            alarmScheduler = alarmScheduler
        )
        tool = EventLifeTool(
            scheduleWakeManager = manager,
            scheduledAlarmDao = scheduledAlarmDao,
            clock = { fakeNow },
            zone = zone
        )
    }

    @Test
    fun testDefinitions_declareCreateAndList() {
        assertEquals(
            listOf(EventLifeTool.TOOL_CREATE, EventLifeTool.TOOL_LIST),
            tool.definitions.map { it.function.name }
        )
    }

    @Test
    fun testExecute_unknownName_returnsNullByContract() = runTest {
        assertNull(tool.execute("not_my_tool", buildJsonObject {}, "call_1"))
    }

    @Test
    fun testCreateEvent_localDateTime_persistsAlarmAndSchedules() = runTest {
        val result = tool.execute(
            EventLifeTool.TOOL_CREATE,
            buildJsonObject {
                put("title", "期中考试")
                put("time", "2026-09-23T15:00")
                put("notes", "带上学生证")
            },
            "call_1"
        )

        assertTrue(result!!.isSuccess)
        assertTrue(result.output.contains("期中考试"))
        val expected = LocalDateTime.parse("2026-09-23T15:00").atZone(zone).toInstant().toEpochMilli()
        coVerify(exactly = 1) {
            scheduledAlarmDao.insert(
                withArg { entity: ScheduledAlarmEntity ->
                    assertEquals(expected, entity.triggerAt)
                    assertTrue(entity.botNote.startsWith("期中考试"))
                    assertTrue(entity.botNote.contains("备注：带上学生证"))
                    assertEquals(ScheduledAlarmEntity.STATUS_PENDING, entity.status)
                }
            )
        }
        verify(exactly = 1) { alarmScheduler.scheduleAlarm(any(), expected) }
    }

    @Test
    fun testCreateEvent_offsetDateTime_parsedExactly() = runTest {
        val result = tool.execute(
            EventLifeTool.TOOL_CREATE,
            buildJsonObject {
                put("title", "取快递")
                put("time", "2026-09-23T15:00:00+08:00")
            },
            "call_1"
        )

        assertTrue(result!!.isSuccess)
        val expected = OffsetDateTime.parse("2026-09-23T15:00:00+08:00").toInstant().toEpochMilli()
        coVerify(exactly = 1) {
            scheduledAlarmDao.insert(withArg { assertEquals(expected, it.triggerAt) })
        }
    }

    @Test
    fun testCreateEvent_dateOnly_defaultsToMorningNine() = runTest {
        val result = tool.execute(
            EventLifeTool.TOOL_CREATE,
            buildJsonObject {
                put("title", "交报告")
                put("time", "2026-09-23")
            },
            "call_1"
        )

        assertTrue(result!!.isSuccess)
        val expected = LocalDateTime.parse("2026-09-23T09:00").atZone(zone).toInstant().toEpochMilli()
        coVerify(exactly = 1) {
            scheduledAlarmDao.insert(withArg { assertEquals(expected, it.triggerAt) })
        }
    }

    @Test
    fun testCreateEvent_pastTime_rejectedWithoutPersistence() = runTest {
        val result = tool.execute(
            EventLifeTool.TOOL_CREATE,
            buildJsonObject {
                put("title", "过去的安排")
                put("time", "2020-01-01T09:00")
            },
            "call_1"
        )

        assertFalse(result!!.isSuccess)
        coVerify(exactly = 0) { scheduledAlarmDao.insert(any()) }
        verify(exactly = 0) { alarmScheduler.scheduleAlarm(any(), any()) }
    }

    @Test
    fun testCreateEvent_unreadableTime_friendlyFailure() = runTest {
        val result = tool.execute(
            EventLifeTool.TOOL_CREATE,
            buildJsonObject {
                put("title", "模糊时间")
                put("time", "下周三下午")
            },
            "call_1"
        )

        assertFalse(result!!.isSuccess)
        assertTrue("失败说明要给模型可转述的中文", result.output.contains("没看懂"))
        coVerify(exactly = 0) { scheduledAlarmDao.insert(any()) }
    }

    @Test
    fun testCreateEvent_blankTitle_friendlyFailure() = runTest {
        val result = tool.execute(
            EventLifeTool.TOOL_CREATE,
            buildJsonObject { put("time", "2026-09-23T15:00") },
            "call_1"
        )

        assertFalse(result!!.isSuccess)
        coVerify(exactly = 0) { scheduledAlarmDao.insert(any()) }
    }

    @Test
    fun testListEvents_ascendingOrderAndTitleOnly() = runTest {
        val e1 = ScheduledAlarmEntity(id = "a", triggerAt = fakeNow + 3_600_000L, botNote = "买伞")
        val e2 = ScheduledAlarmEntity(id = "b", triggerAt = fakeNow + 7_200_000L, botNote = "交报告\n备注：给导师")
        coEvery { scheduledAlarmDao.getUpcomingAlarms(any(), any(), any()) } returns listOf(e1, e2)

        val result = tool.execute(EventLifeTool.TOOL_LIST, buildJsonObject {}, "call_1")

        assertTrue(result!!.isSuccess)
        val idxBuy = result.output.indexOf("买伞")
        val idxReport = result.output.indexOf("交报告")
        assertTrue("列表必须按时间升序", idxBuy in 0 until idxReport)
        assertFalse("列表只展示事件名，不展示备注", result.output.contains("备注：给导师"))
    }

    @Test
    fun testListEvents_empty_returnsFriendlyText() = runTest {
        // relaxed DAO 默认返回空列表
        val result = tool.execute(EventLifeTool.TOOL_LIST, buildJsonObject {}, "call_1")

        assertTrue(result!!.isSuccess)
        assertTrue(result.output.contains("没有安排"))
    }

    @Test
    fun testListEvents_daysClampedToMax30() = runTest {
        coEvery { scheduledAlarmDao.getUpcomingAlarms(any(), any(), any()) } returns emptyList()

        tool.execute(EventLifeTool.TOOL_LIST, buildJsonObject { put("days", 999) }, "call_1")

        coVerify(exactly = 1) {
            scheduledAlarmDao.getUpcomingAlarms(
                nowMs = eq(fakeNow),
                untilMs = eq(fakeNow + 30L * 86_400_000L),
                limit = eq(EventLifeTool.MAX_LIST_COUNT)
            )
        }
    }
}
