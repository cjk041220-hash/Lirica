package com.lirica.android.database.memory

import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯 Kotlin 内存/持久化双层向量检索存储库 (Memory Vector Store)
 *
 * 核心设计决策（D21 授权选型）：
 * 1. 单用户记忆体量（千条级），在纯 Kotlin 下计算 512 维向量点积耗时仅 ~1ms，性能完全过剩且零 native 依赖。
 * 2. 避免 sqlite-vec 在 Android/Robolectric 上因 libsqlite 限制引发动态链接灾难。
 * 3. 内存 ConcurrentHashMap 缓存稠密向量，Room (memory_embeddings) 负责持久化与异步回写。
 */
class MemoryVectorStore(
    private val dao: MemoryEmbeddingDao? = null
) {

    data class VectorSearchResult(
        val memoryId: String,
        val track: String,
        val similarity: Float
    )

    private data class CachedVector(
        val memoryId: String,
        val track: String,
        val vector: FloatArray,
        val updatedAt: Long
    )

    private val cache = ConcurrentHashMap<String, CachedVector>()

    /**
     * 从持久化数据库加载全部已保存的向量索引至内存
     */
    suspend fun initFromDatabase() {
        if (dao == null) return
        val allEntities = dao.getAll()
        for (entity in allEntities) {
            cache[entity.memoryId] = CachedVector(
                memoryId = entity.memoryId,
                track = entity.track,
                vector = entity.toFloatArray(),
                updatedAt = entity.updatedAt
            )
        }
    }

    /**
     * 写入或更新单个记忆向量
     */
    suspend fun upsert(memoryId: String, track: String, embedding: FloatArray) {
        val now = System.currentTimeMillis()
        cache[memoryId] = CachedVector(
            memoryId = memoryId,
            track = track,
            vector = embedding.copyOf(),
            updatedAt = now
        )

        dao?.let {
            val entity = MemoryEmbeddingEntity.fromFloatArray(
                memoryId = memoryId,
                track = track,
                vector = embedding,
                updatedAt = now
            )
            it.insertOrUpdate(entity)
        }
    }

    /**
     * 批量写入记忆向量
     */
    suspend fun upsertBatch(items: List<Triple<String, String, FloatArray>>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = mutableListOf<MemoryEmbeddingEntity>()

        for ((id, track, vec) in items) {
            cache[id] = CachedVector(
                memoryId = id,
                track = track,
                vector = vec.copyOf(),
                updatedAt = now
            )
            if (dao != null) {
                entities.add(
                    MemoryEmbeddingEntity.fromFloatArray(
                        memoryId = id,
                        track = track,
                        vector = vec,
                        updatedAt = now
                    )
                )
            }
        }

        dao?.insertOrUpdateAll(entities)
    }

    /**
     * 删除记忆向量
     */
    suspend fun delete(memoryId: String) {
        cache.remove(memoryId)
        dao?.deleteByMemoryId(memoryId)
    }

    /**
     * 清空全部向量
     */
    suspend fun clear() {
        cache.clear()
        dao?.clearAll()
    }

    /**
     * 当前缓存的向量数量
     */
    fun size(track: String? = null): Int {
        return if (track == null) {
            cache.size
        } else {
            cache.values.count { it.track == track }
        }
    }

    /**
     * 获取指定记忆的向量
     */
    fun getVector(memoryId: String): FloatArray? {
        return cache[memoryId]?.vector?.copyOf()
    }

    /**
     * 暴力余弦相似度检索 (Brute-Force Cosine Search)
     *
     * 由于所有输入向量均在 EmbeddingModel 中完成了 L2 单位长度归一化，
     * 余弦相似度直接等于向量内积 (dot product)。
     * 使用最小堆维持 top-K 结果，复杂度 O(N * 512 + N log K)。
     */
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 20,
        track: String? = null
    ): List<VectorSearchResult> {
        if (cache.isEmpty() || topK <= 0) return emptyList()

        // 维护大小为 topK 的最小堆（堆顶是当前 topK 中相似度最低者）
        val minHeap = PriorityQueue<VectorSearchResult>(topK) { a, b ->
            a.similarity.compareTo(b.similarity)
        }

        for (item in cache.values) {
            if (track != null && item.track != track) {
                continue
            }

            val sim = dotProduct(queryEmbedding, item.vector)

            if (minHeap.size < topK) {
                minHeap.offer(VectorSearchResult(item.memoryId, item.track, sim))
            } else {
                val lowest = minHeap.peek()
                if (lowest != null && sim > lowest.similarity) {
                    minHeap.poll()
                    minHeap.offer(VectorSearchResult(item.memoryId, item.track, sim))
                }
            }
        }

        // 倒序弹出，使最终列表按相似度从大到小排列
        val result = mutableListOf<VectorSearchResult>()
        while (minHeap.isNotEmpty()) {
            val item = minHeap.poll() ?: break
            result.add(item)
        }
        result.reverse()
        return result
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val len = a.size
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }
}
