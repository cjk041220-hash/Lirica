package com.lirica.android.database.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.database.LiricaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MemoryEmbeddingDaoTest {

    private lateinit var database: LiricaDatabase
    private lateinit var dao: MemoryEmbeddingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = LiricaDatabase.buildInMemory(context)
        dao = database.memoryEmbeddingDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertAndGetByMemoryId() = runBlocking {
        val vector = FloatArray(512) { it.toFloat() / 512f }
        val entity = MemoryEmbeddingEntity.fromFloatArray(
            memoryId = "mem_user_001",
            track = MemoryEmbeddingEntity.TRACK_USER,
            vector = vector
        )

        dao.insertOrUpdate(entity)

        val retrieved = dao.getByMemoryId("mem_user_001")
        assertNotNull(retrieved)
        assertEquals("mem_user_001", retrieved!!.memoryId)
        assertEquals(MemoryEmbeddingEntity.TRACK_USER, retrieved.track)

        val restoredVector = retrieved.toFloatArray()
        assertEquals(512, restoredVector.size)
        assertEquals(vector[0], restoredVector[0], 1e-6f)
        assertEquals(vector[256], restoredVector[256], 1e-6f)
        assertEquals(vector[511], restoredVector[511], 1e-6f)
    }

    @Test
    fun testTrackFilteringAndCount() = runBlocking {
        val v = FloatArray(512) { 0.5f }
        val u1 = MemoryEmbeddingEntity.fromFloatArray("u1", MemoryEmbeddingEntity.TRACK_USER, v)
        val u2 = MemoryEmbeddingEntity.fromFloatArray("u2", MemoryEmbeddingEntity.TRACK_USER, v)
        val s1 = MemoryEmbeddingEntity.fromFloatArray("s1", MemoryEmbeddingEntity.TRACK_SELF, v)

        dao.insertOrUpdateAll(listOf(u1, u2, s1))

        assertEquals(3, dao.count())
        assertEquals(2, dao.getByTrack(MemoryEmbeddingEntity.TRACK_USER).size)
        assertEquals(1, dao.getByTrack(MemoryEmbeddingEntity.TRACK_SELF).size)
    }

    @Test
    fun testDelete() = runBlocking {
        val v = FloatArray(512) { 1.0f }
        val e = MemoryEmbeddingEntity.fromFloatArray("d1", "user", v)
        dao.insertOrUpdate(e)
        assertEquals(1, dao.count())

        dao.deleteByMemoryId("d1")
        assertNull(dao.getByMemoryId("d1"))
        assertEquals(0, dao.count())
    }

    @Test
    fun testVectorStorePersistenceIntegration() = runBlocking {
        val vectorStore = MemoryVectorStore(dao)

        val vec = FloatArray(512) { 0.2f }
        vectorStore.upsert("p1", "user", vec)

        // 创建新 store 模拟冷启动读取数据库
        val newStore = MemoryVectorStore(dao)
        assertEquals(0, newStore.size())
        newStore.initFromDatabase()
        assertEquals(1, newStore.size())

        val results = newStore.search(vec, topK = 1)
        assertEquals(1, results.size)
        assertEquals("p1", results[0].memoryId)
    }
}
