package com.lirica.android.database.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MemoryVectorStoreTest {

    @Test
    fun testUpsertAndSearchTopK() = runBlocking {
        val store = MemoryVectorStore()

        // 构造简单的单位向量
        val vec1 = FloatArray(512) { 0f }.apply { this[0] = 1f } // 与 query 最像
        val vec2 = FloatArray(512) { 0f }.apply { this[0] = 0.8f; this[1] = 0.6f }
        val vec3 = FloatArray(512) { 0f }.apply { this[10] = 1f } // 正交，相似度 0

        store.upsert("mem_1", MemoryEmbeddingEntity.TRACK_USER, vec1)
        store.upsert("mem_2", MemoryEmbeddingEntity.TRACK_USER, vec2)
        store.upsert("mem_3", MemoryEmbeddingEntity.TRACK_USER, vec3)

        val query = FloatArray(512) { 0f }.apply { this[0] = 1f }
        val results = store.search(query, topK = 2)

        assertEquals(2, results.size)
        assertEquals("mem_1", results[0].memoryId)
        assertEquals(1.0f, results[0].similarity, 1e-5f)

        assertEquals("mem_2", results[1].memoryId)
        assertEquals(0.8f, results[1].similarity, 1e-5f)
    }

    @Test
    fun testTrackFiltering() = runBlocking {
        val store = MemoryVectorStore()

        val vec = FloatArray(512) { 0.1f }
        store.upsert("user_1", MemoryEmbeddingEntity.TRACK_USER, vec)
        store.upsert("self_1", MemoryEmbeddingEntity.TRACK_SELF, vec)

        val userResults = store.search(vec, topK = 10, track = MemoryEmbeddingEntity.TRACK_USER)
        assertEquals(1, userResults.size)
        assertEquals("user_1", userResults[0].memoryId)

        val selfResults = store.search(vec, topK = 10, track = MemoryEmbeddingEntity.TRACK_SELF)
        assertEquals(1, selfResults.size)
        assertEquals("self_1", selfResults[0].memoryId)

        val allResults = store.search(vec, topK = 10, track = null)
        assertEquals(2, allResults.size)
    }

    @Test
    fun testDeleteAndClear() = runBlocking {
        val store = MemoryVectorStore()
        val vec = FloatArray(512) { 0.5f }

        store.upsert("m1", "user", vec)
        store.upsert("m2", "user", vec)
        assertEquals(2, store.size())

        store.delete("m1")
        assertEquals(1, store.size())

        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun testScaleAndPerformanceWith5000Vectors() = runBlocking {
        val store = MemoryVectorStore()
        val rng = Random(42)

        val items = (1..5000).map { i ->
            val v = FloatArray(512) { rng.nextFloat() - 0.5f }
            // 归一化
            var s = 0f
            for (f in v) s += f * f
            val norm = Math.sqrt(s.toDouble()).toFloat()
            for (d in 0 until 512) v[d] /= norm
            Triple("mem_$i", "user", v)
        }

        store.upsertBatch(items)
        assertEquals(5000, store.size())

        val query = FloatArray(512) { rng.nextFloat() - 0.5f }
        var s = 0f
        for (f in query) s += f * f
        val norm = Math.sqrt(s.toDouble()).toFloat()
        for (d in 0 until 512) query[d] /= norm

        // 预热 1 次
        store.search(query, topK = 20)

        val start = System.currentTimeMillis()
        val count = 20
        for (i in 0 until count) {
            val res = store.search(query, topK = 20)
            assertEquals(20, res.size)
        }
        val elapsed = System.currentTimeMillis() - start
        val avgMs = elapsed.toDouble() / count
        println("MemoryVectorStore 5000 向量检索平均耗时: ${avgMs}ms / 次")

        // 5000 向量检索在纯 Kotlin 下应远低于 10ms
        assertTrue("检索耗时应低于 10ms", avgMs < 10.0)
    }
}
