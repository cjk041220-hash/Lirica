package com.lirica.android.memory.consolidation

import com.lirica.android.database.entity.UserMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeliefRevisionEngineTest {

    private val engine = BeliefRevisionEngine()

    @Test
    fun testDetectAndRevise_coffeeContradiction_supersedesOldFact() {
        val oldMemory = UserMemoryEntity(
            id = "mem_old_coffee",
            content = "用户喜欢常喝美式黑咖啡提神",
            category = "饮食偏好",
            createdAt = 1000L,
            status = UserMemoryEntity.STATUS_ACTIVE
        )
        val newMemory = UserMemoryEntity(
            id = "mem_new_tea",
            content = "用户最近胃酸过多，医生建议戒掉咖啡改喝大麦茶",
            category = "饮食偏好",
            createdAt = 2000L,
            status = UserMemoryEntity.STATUS_ACTIVE
        )

        val result = engine.detectAndRevise(
            existingMemories = listOf(oldMemory),
            newMemories = listOf(newMemory),
            nowMs = 2500L
        )

        assertEquals(1, result.supersededMemories.size)
        assertEquals(1, result.activeMemories.size)

        val revisedOld = result.supersededMemories.first()
        assertEquals("mem_old_coffee", revisedOld.id)
        assertEquals(UserMemoryEntity.STATUS_SUPERSEDED, revisedOld.status)
        assertEquals("mem_new_tea", revisedOld.supersededBy)
        assertEquals("用户喜欢常喝美式黑咖啡提神", revisedOld.prevContent)
        assertEquals(2500L, revisedOld.validTo)

        val active = result.activeMemories.first()
        assertEquals("mem_new_tea", active.id)
        assertEquals(UserMemoryEntity.STATUS_ACTIVE, active.status)
    }

    @Test
    fun testDetectAndRevise_spicyFoodContradiction_supersedesOldFact() {
        val oldMemory = UserMemoryEntity(
            id = "mem_spicy",
            content = "用户爱吃重度麻辣火锅",
            category = "饮食偏好",
            createdAt = 1000L,
            status = UserMemoryEntity.STATUS_ACTIVE
        )
        val newMemory = UserMemoryEntity(
            id = "mem_no_spicy",
            content = "用户胃炎发作，目前严禁吃辣，改吃清淡温热食物",
            category = "饮食偏好",
            createdAt = 2000L,
            status = UserMemoryEntity.STATUS_ACTIVE
        )

        val result = engine.detectAndRevise(
            existingMemories = listOf(oldMemory),
            newMemories = listOf(newMemory),
            nowMs = 2500L
        )

        assertEquals(1, result.supersededMemories.size)
        val revised = result.supersededMemories.first()
        assertEquals("mem_spicy", revised.id)
        assertEquals("mem_no_spicy", revised.supersededBy)
    }

    @Test
    fun testDetectAndRevise_noContradiction_keepsAllActive() {
        val m1 = UserMemoryEntity(id = "1", content = "用户喜欢科幻电影", category = "兴趣", createdAt = 1000L)
        val m2 = UserMemoryEntity(id = "2", content = "用户喜欢摇滚音乐", category = "兴趣", createdAt = 2000L)

        val result = engine.detectAndRevise(listOf(m1), listOf(m2), 3000L)
        assertTrue(result.supersededMemories.isEmpty())
        assertEquals(2, result.activeMemories.size)
    }
}
