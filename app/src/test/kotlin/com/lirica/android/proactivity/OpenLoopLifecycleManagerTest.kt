package com.lirica.android.proactivity

import com.lirica.android.database.dao.OpenLoopDao
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.OpenLoopEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
class OpenLoopLifecycleManagerTest {

    private val loops = mutableListOf<OpenLoopEntity>()
    private val thoughts = mutableListOf<UnspokenThoughtEntity>()
    private var currentTime = 1000000L

    private val fakeLoopDao = object : OpenLoopDao {
        override suspend fun insert(entity: OpenLoopEntity): Long {
            loops.add(entity)
            return loops.size.toLong()
        }
        override suspend fun insertAll(entities: List<OpenLoopEntity>) {
            loops.addAll(entities)
        }
        override suspend fun update(entity: OpenLoopEntity) {
            val idx = loops.indexOfFirst { it.id == entity.id }
            if (idx != -1) loops[idx] = entity
        }
        override suspend fun getById(id: String): OpenLoopEntity? = loops.find { it.id == id }
        override suspend fun getByStatus(status: String): List<OpenLoopEntity> = loops.filter { it.status == status }
        override suspend fun getDueForTracking(currentTimeMs: Long): List<OpenLoopEntity> {
            return loops.filter { (it.status == OpenLoopEntity.STATUS_OPEN || it.status == OpenLoopEntity.STATUS_TRACKING) && it.triggerWindowStartMs <= currentTimeMs }
        }
        override suspend fun updateStatus(id: String, newStatus: String) {
            val idx = loops.indexOfFirst { it.id == id }
            if (idx != -1) loops[idx] = loops[idx].copy(status = newStatus)
        }
        override suspend fun markPrompted(id: String, promptedMs: Long, newStatus: String) {
            val idx = loops.indexOfFirst { it.id == id }
            if (idx != -1) loops[idx] = loops[idx].copy(status = newStatus, lastPromptedMs = promptedMs, retryCount = loops[idx].retryCount + 1)
        }
        override suspend fun expireOldLoops(expiryThresholdMs: Long): Int {
            var count = 0
            for (i in loops.indices) {
                if (loops[i].status in listOf("OPEN", "TRACKING", "TRIGGERED") && loops[i].targetTimeMs < expiryThresholdMs) {
                    loops[i] = loops[i].copy(status = OpenLoopEntity.STATUS_EXPIRED)
                    count++
                }
            }
            return count
        }
        override suspend fun cleanupClosedLoops(beforeMs: Long): Int = 0
    }

    private val fakeThoughtDao = object : UnspokenThoughtDao {
        override suspend fun insert(entity: UnspokenThoughtEntity): Long {
            thoughts.add(entity)
            return thoughts.size.toLong()
        }
        override suspend fun insertAll(entities: List<UnspokenThoughtEntity>) { thoughts.addAll(entities) }
        override suspend fun update(entity: UnspokenThoughtEntity) {}
        override suspend fun delete(entity: UnspokenThoughtEntity) {}
        override suspend fun deleteById(id: String) { thoughts.removeAll { it.id == id } }
        override suspend fun getById(id: String): UnspokenThoughtEntity? = thoughts.find { it.id == id }
        override fun getPendingThoughts(status: String, limit: Int): kotlinx.coroutines.flow.Flow<List<UnspokenThoughtEntity>> {
            return kotlinx.coroutines.flow.flowOf(thoughts.filter { it.status == status }.take(limit))
        }
        override suspend fun getTopUrgentPendingThoughts(status: String, limit: Int): List<UnspokenThoughtEntity> {
            return thoughts.filter { it.status == status }.sortedByDescending { it.urgency }.take(limit)
        }
        override suspend fun markConsumed(id: String, consumedAt: Long, consumedStatus: String) {
            val idx = thoughts.indexOfFirst { it.id == id }
            if (idx != -1) thoughts[idx] = thoughts[idx].copy(status = consumedStatus, consumedAt = consumedAt)
        }
        override suspend fun markDiscarded(id: String, discardedStatus: String) {
            val idx = thoughts.indexOfFirst { it.id == id }
            if (idx != -1) thoughts[idx] = thoughts[idx].copy(status = discardedStatus)
        }
        override suspend fun discardExpiredThoughts(currentTimestamp: Long, pendingStatus: String, discardedStatus: String): Int = 0
        override suspend fun updateDecayScore(id: String, newScore: Float) {}
    }

    private lateinit var manager: OpenLoopLifecycleManager

    @Before
    fun setUp() {
        loops.clear()
        thoughts.clear()
        currentTime = 1000000L
        manager = OpenLoopLifecycleManager(
            openLoopDao = fakeLoopDao,
            unspokenThoughtDao = fakeThoughtDao,
            timeProvider = { currentTime }
        )
    }

    @Test
    fun testCreateLoop_persistsAndCalculatesWindow() = runBlocking {
        val targetTime = currentTime + 4 * 3600 * 1000L // 4 小时后
        val loop = manager.createLoop(
            title = "毕业答辩",
            targetTimeMs = targetTime,
            leadTimeMs = 2 * 3600 * 1000L
        )
        assertEquals(1, loops.size)
        assertEquals("毕业答辩", loop.title)
        assertEquals(OpenLoopEntity.STATUS_OPEN, loop.status)
        assertEquals(targetTime - 2 * 3600 * 1000L, loop.triggerWindowStartMs)
    }

    @Test
    fun testCheckAndPromoteDueLoops_triggersWhenInWindow() = runBlocking {
        val targetTime = currentTime + 3600 * 1000L // 1 小时后
        manager.createLoop(
            title = "医院挂号看诊",
            targetTimeMs = targetTime,
            leadTimeMs = 2 * 3600 * 1000L // 提前2小时触发
        )

        // 当前时间已经进入触发窗口 (currentTime >= targetTime - 2h)
        val promoted = manager.checkAndPromoteDueLoops(currentTime)
        assertEquals(1, promoted.size)
        assertEquals(OpenLoopEntity.STATUS_TRIGGERED, promoted[0].status)

        // 验证念头池已注入关怀念头
        assertEquals(1, thoughts.size)
        assertTrue(thoughts[0].thought.contains("医院挂号看诊"))
        assertTrue(thoughts[0].triggerContext!!.startsWith("open_loop:"))
    }

    @Test
    fun testResolveLoop_closesSuccessfully() = runBlocking {
        val loop = manager.createLoop(
            title = "提交期末大作业",
            targetTimeMs = currentTime + 10000L
        )
        manager.resolveLoop(loop.id)
        val updated = fakeLoopDao.getById(loop.id)
        assertEquals(OpenLoopEntity.STATUS_RESOLVED, updated?.status)
    }

    @Test
    fun testExpireOverdueLoops_expiresAfter72Hours() = runBlocking {
        val pastTime = currentTime - 80 * 3600 * 1000L // 80 小时前已到期
        val loop = manager.createLoop(
            title = "上周的聚餐",
            targetTimeMs = pastTime
        )
        val expiredCount = manager.expireOverdueLoops(currentTime)
        assertEquals(1, expiredCount)
        val updated = fakeLoopDao.getById(loop.id)
        assertEquals(OpenLoopEntity.STATUS_EXPIRED, updated?.status)
    }
}