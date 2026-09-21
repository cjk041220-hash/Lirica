package com.xiaoxi.android.proactivity

import com.xiaoxi.android.database.dao.ScheduledAlarmDao
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScheduleWakeManagerTest {

    private val scheduledAlarmDao: ScheduledAlarmDao = mockk(relaxed = true)
    private val unspokenThoughtDao: UnspokenThoughtDao = mockk(relaxed = true)
    private val systemAlarmScheduler: SystemAlarmScheduler = mockk(relaxed = true)

    private lateinit var manager: ScheduleWakeManager

    @Before
    fun setUp() {
        manager = ScheduleWakeManager(
            scheduledAlarmDao = scheduledAlarmDao,
            unspokenThoughtDao = unspokenThoughtDao,
            alarmScheduler = systemAlarmScheduler
        )
    }

    @Test
    fun testScheduleWake_persistsToDaoAndSchedulesSystemAlarm() = runTest {
        val triggerAt = 1726880000000L
        val botNote = "两小时后提醒主人喝水休息"
        val blockDelivery = true

        val entitySlot = slot<ScheduledAlarmEntity>()
        coEvery { scheduledAlarmDao.insert(capture(entitySlot)) } returns 1L

        val alarm = manager.scheduleWake(triggerAt, botNote, blockDelivery)

        assertNotNull(alarm.id)
        assertEquals(triggerAt, alarm.triggerAt)
        assertEquals(botNote, alarm.botNote)
        assertTrue(alarm.blockDelivery)
        assertEquals(ScheduledAlarmEntity.STATUS_PENDING, alarm.status)

        coVerify { scheduledAlarmDao.insert(any()) }
        coVerify { systemAlarmScheduler.scheduleAlarm(alarm.id, triggerAt) }
    }

    @Test
    fun testCancelWake_updatesDaoAndCancelsSystemAlarm() = runTest {
        val alarmId = "alarm_test_123"
        coJustRun { scheduledAlarmDao.updateStatus(alarmId, ScheduledAlarmEntity.STATUS_CANCELLED) }

        manager.cancelWake(alarmId)

        coVerify { scheduledAlarmDao.updateStatus(alarmId, ScheduledAlarmEntity.STATUS_CANCELLED) }
        coVerify { systemAlarmScheduler.cancelAlarm(alarmId) }
    }

    @Test
    fun testOnWakeTriggered_updatesStatusAndSynthesizesUnspokenThought() = runTest {
        val alarmId = "alarm_wake_456"
        val alarmEntity = ScheduledAlarmEntity(
            id = alarmId,
            triggerAt = 1000L,
            botNote = "记得给猫咪加粮",
            blockDelivery = false,
            status = ScheduledAlarmEntity.STATUS_PENDING
        )

        coEvery { scheduledAlarmDao.getPendingAlarms(any()) } returns listOf(alarmEntity)
        val thoughtSlot = slot<UnspokenThoughtEntity>()
        coEvery { unspokenThoughtDao.insert(capture(thoughtSlot)) } returns 1L

        manager.onWakeTriggered(alarmEntity, nowMs = 1000L)

        coVerify { scheduledAlarmDao.updateStatus(alarmId, ScheduledAlarmEntity.STATUS_TRIGGERED) }
        coVerify { unspokenThoughtDao.insert(any()) }

        val createdThought = thoughtSlot.captured
        assertTrue(createdThought.thought.contains("记得给猫咪加粮"))
        assertEquals(4.8f, createdThought.urgency, 0.01f)
        assertEquals(UnspokenThoughtEntity.STATUS_PENDING, createdThought.status)
    }

    @Test
    fun testIsDeliveryBlocked_whenPendingAlarmBlocksDelivery_returnsTrue() = runTest {
        val pendingAlarm = ScheduledAlarmEntity(
            id = "alarm_block",
            triggerAt = 5000L,
            botNote = "专注工作中，勿扰",
            blockDelivery = true,
            status = ScheduledAlarmEntity.STATUS_PENDING
        )

        coEvery { scheduledAlarmDao.getPendingAlarms(any()) } returns listOf(pendingAlarm)

        val isBlocked = manager.isDeliveryBlocked(nowMs = 2000L)
        assertTrue(isBlocked)
    }

    @Test
    fun testIsDeliveryBlocked_whenNoPendingAlarm_returnsFalse() = runTest {
        coEvery { scheduledAlarmDao.getPendingAlarms(any()) } returns emptyList()

        val isBlocked = manager.isDeliveryBlocked(nowMs = 2000L)
        assertFalse(isBlocked)
    }
}
