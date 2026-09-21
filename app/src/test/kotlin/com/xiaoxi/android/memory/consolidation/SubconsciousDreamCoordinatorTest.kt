package com.xiaoxi.android.memory.consolidation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.database.XiaoxiDatabase
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.proactivity.DiaryEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SubconsciousDreamCoordinatorTest {

    private lateinit var database: XiaoxiDatabase
    private val mockDiaryEngine = mockk<DiaryEngine>(relaxed = true)
    private lateinit var coordinator: SubconsciousDreamCoordinator

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            XiaoxiDatabase::class.java
        ).allowMainThreadQueries().build()

        coordinator = SubconsciousDreamCoordinator(
            userMemoryDao = database.userMemoryDao(),
            worldviewDao = database.worldviewDao(),
            selfExperienceDao = database.selfExperienceDao(),
            unspokenThoughtDao = database.unspokenThoughtDao(),
            diaryEngine = mockDiaryEngine
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRunDreamCycle_fullPipelineExecution_antiHoardingAndContradictionResolution() = runBlocking {
        val userMemoryDao = database.userMemoryDao()
        val nowMs = LocalDate.of(2026, 9, 21)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        // 1. 注入待整理记忆：包含重复耳机事实、相对时间事实、矛盾咖啡与大麦茶事实
        val m1 = UserMemoryEntity(
            id = "m1_headphone_1",
            content = "用户买了新降噪耳机",
            category = "偏好",
            importance = 5,
            confidence = 0.8f,
            createdAt = nowMs - 86400000L,
            accessCount = 1,
            hitCount = 1
        )
        val m2 = UserMemoryEntity(
            id = "m2_headphone_2",
            content = "用户喜欢索尼降噪耳机注重音质",
            category = "偏好",
            importance = 8,
            confidence = 0.95f,
            createdAt = nowMs - 43200000L,
            accessCount = 3,
            hitCount = 2
        )
        val m3 = UserMemoryEntity(
            id = "m3_relative_time",
            content = "用户明天要去北京出差",
            category = "日程",
            importance = 6,
            confidence = 0.9f,
            createdAt = nowMs,
            accessCount = 1,
            hitCount = 1
        )
        val m4 = UserMemoryEntity(
            id = "m4_old_coffee",
            content = "用户常喝美式黑咖啡提神",
            category = "饮食偏好",
            importance = 6,
            confidence = 0.85f,
            createdAt = nowMs - 100000000L,
            accessCount = 5,
            hitCount = 4
        )
        val m5 = UserMemoryEntity(
            id = "m5_new_tea",
            content = "用户胃酸过多医生建议戒掉咖啡改喝大麦茶",
            category = "饮食偏好",
            importance = 8,
            confidence = 0.95f,
            createdAt = nowMs - 100000L,
            accessCount = 2,
            hitCount = 2
        )
        val m6 = UserMemoryEntity(
            id = "m6_deep_work",
            content = "用户习惯在工作时开启全屏免打扰进行深度工作",
            category = "工作习惯",
            importance = 9,
            confidence = 0.95f,
            createdAt = nowMs - 200000000L,
            accessCount = 6,
            hitCount = 5 // 高频验证，应晋升世界观
        )

        userMemoryDao.insertAll(listOf(m1, m2, m3, m4, m5, m6))

        // 2. 执行夜间入梦整理循环
        val report = coordinator.runDreamCycle(nowMs)

        // 3. 断言反囤积铁律：活跃条目数具备收敛性
        assertTrue(report.survivingCount <= report.originalCount)
        assertEquals(6, report.originalCount)
        assertEquals(1, report.archivedCount) // m1_headphone_1 被合并归档

        // 4. 断言相对时间已被绝对化
        val timeMemory = userMemoryDao.getById("m3_relative_time")
        assertNotNull(timeMemory)
        assertTrue(timeMemory!!.content.contains("2026-09-22"))

        // 5. 断言矛盾已被消歧：旧咖啡记忆被标记为 superseded
        val supersededCoffee = userMemoryDao.getById("m4_old_coffee")
        assertNotNull(supersededCoffee)
        assertEquals(UserMemoryEntity.STATUS_SUPERSEDED, supersededCoffee!!.status)
        assertEquals("m5_new_tea", supersededCoffee.supersededBy)

        // 6. 断言世界观晋升：m6 深度工作习惯被晋升
        val worldviewDao = database.worldviewDao()
        val allWorldviews = worldviewDao.getAll()
        assertTrue(allWorldviews.isNotEmpty())
        assertTrue(allWorldviews.any { it.category == "工作习惯" && it.statement.contains("深度工作") })

        // 7. 断言清晨梦醒浮现活水：生成了一条未完成念头存入念头池
        val unspokenDao = database.unspokenThoughtDao()
        val thoughts = unspokenDao.getTopUrgentPendingThoughts(limit = 10)
        assertTrue(thoughts.isNotEmpty())
        assertNotNull(report.emergenceThoughtId)
    }
}
