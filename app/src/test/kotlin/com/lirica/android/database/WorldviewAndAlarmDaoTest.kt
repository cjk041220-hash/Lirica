package com.lirica.android.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.database.entity.WorldviewEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WorldviewAndAlarmDaoTest {

    private lateinit var database: LiricaDatabase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiricaDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testWorldviewDao_crudAndCategoryQuery() = runBlocking {
        val dao = database.worldviewDao()
        val entry1 = WorldviewEntity(
            id = "wv_1",
            category = "生活习惯",
            statement = "用户习惯每晚 11 点后入睡，喜欢在睡前听轻音乐",
            supportingEvidenceCount = 5,
            lastConfirmedAt = 1726848000000L,
            confidence = 0.95f
        )
        val entry2 = WorldviewEntity(
            id = "wv_2",
            category = "工作哲学",
            statement = "用户倾向于采用深度工作法，厌恶碎片化的临时打断",
            supportingEvidenceCount = 8,
            lastConfirmedAt = 1726849000000L,
            confidence = 1.0f
        )

        dao.insertAll(listOf(entry1, entry2))

        val all = dao.getAll()
        assertEquals(2, all.size)

        val habitList = dao.getByCategory("生活习惯")
        assertEquals(1, habitList.size)
        assertEquals("wv_1", habitList.first().id)

        val top = dao.getTopConfirmed(limit = 1)
        assertEquals(1, top.size)
        assertEquals("wv_2", top.first().id)
        assertEquals(8, top.first().supportingEvidenceCount)
    }

    @Test
    fun testScheduledAlarmDao_crudAndStatusTransitions() = runBlocking {
        val dao = database.scheduledAlarmDao()
        val alarm = ScheduledAlarmEntity(
            id = "alarm_1",
            triggerAt = 1726850000000L,
            botNote = "提醒喝温开水与休息眼睛",
            blockDelivery = true,
            status = ScheduledAlarmEntity.STATUS_PENDING
        )

        dao.insert(alarm)

        val pending = dao.getPendingAlarms(nowMs = 1726851000000L)
        assertEquals(1, pending.size)
        assertEquals("alarm_1", pending.first().id)
        assertTrue(pending.first().blockDelivery)

        dao.updateStatus(id = "alarm_1", status = ScheduledAlarmEntity.STATUS_TRIGGERED)
        val pendingAfter = dao.getPendingAlarms(nowMs = 1726851000000L)
        assertTrue(pendingAfter.isEmpty())
    }

    @Test
    fun testSelfExperience_isPlaceholderField() = runBlocking {
        val dao = database.selfExperienceDao()
        val realExp = SelfExperienceEntity(
            id = "exp_real",
            title = "真实互动感悟",
            content = "今天和用户讨论了架构演进",
            createdAt = 1000L,
            isPlaceholder = false
        )
        val placeholderExp = SelfExperienceEntity(
            id = "exp_placeholder",
            title = "独处占位",
            content = "今天用户没有发消息",
            createdAt = 2000L,
            isPlaceholder = true
        )

        dao.insertAll(listOf(realExp, placeholderExp))

        val fetchedReal = dao.getById("exp_real")
        assertNotNull(fetchedReal)
        assertFalse(fetchedReal!!.isPlaceholder)

        val fetchedPlaceholder = dao.getById("exp_placeholder")
        assertNotNull(fetchedPlaceholder)
        assertTrue(fetchedPlaceholder!!.isPlaceholder)
    }
}
