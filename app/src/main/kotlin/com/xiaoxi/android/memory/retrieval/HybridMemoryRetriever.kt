package com.xiaoxi.android.memory.retrieval

import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.memory.MemoryEmbeddingEntity
import com.xiaoxi.android.database.memory.MemoryScorer
import com.xiaoxi.android.database.memory.MemoryVectorStore

/**
 * 记忆检索融合器 (Hybrid Memory Retriever)
 *
 * 遵循 PRD §3 D21 规格与批次 2.5 要求：
 * 检索融合 = 向量召回 top-20 → MemoryScorer 六维重排 → 返回最终 top-N。
 * 只新增融合层，严格保持 MemoryScorer 既有实现只读。
 *
 * 1. 向量召回 (Dense Vector Recall)：
 *    端侧模型将查询 query 向量化为 512 维归一化向量，
 *    在 MemoryVectorStore 中快速点积召回语义相似度最高的 top-20 候选。
 *    解决纯字符 2-gram 无法召回同义词/别名（如"周董"召回"喜欢周杰伦"）的语义空白。
 *
 * 2. 认知精排 (Cognitive Re-ranking)：
 *    对 top-20 候选候选实体调用既有 MemoryScorer 计算六维得分
 *    （时间衰减 + 访问热度 + 最近使用 + 重要度 + 话题重叠 + 情绪亲和度），
 *    融合向量相似度进行综合排序。
 *
 * 3. 最终截断：
 *    截取前 topK 条记忆输出。
 */
class HybridMemoryRetriever(
    private val embeddingModel: EmbeddingModel,
    private val vectorStore: MemoryVectorStore,
    private val userMemoryDao: UserMemoryDao,
    private val vectorWeight: Float = 1.0f
) {

    data class ScoredMemory(
        val memory: UserMemoryEntity,
        val totalScore: Float,
        val vectorSimilarity: Float,
        val scorerScore: Float
    )

    /**
     * 核心融合检索接口：向量召回 top-20 → MemoryScorer 六维重排 → 返回 top-N
     */
    suspend fun retrieve(
        query: String,
        topK: Int = 10,
        nowTs: Long = System.currentTimeMillis(),
        moodValence: Float? = null
    ): List<UserMemoryEntity> {
        return retrieveScored(query, topK, nowTs, moodValence).map { it.memory }
    }

    /**
     * 带打分明细的检索融合接口 (用于单测断言与认知调试)
     */
    suspend fun retrieveScored(
        query: String,
        topK: Int = 10,
        nowTs: Long = System.currentTimeMillis(),
        moodValence: Float? = null
    ): List<ScoredMemory> {
        if (topK <= 0) return emptyList()

        // 1. 若 query 为空，退化为基于活跃度与基础认知打分
        if (query.isBlank()) {
            val allActive = userMemoryDao.getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 200)
            return allActive
                .map { mem ->
                    val score = MemoryScorer.calculateScore(mem, "", nowTs, moodValence)
                    ScoredMemory(mem, score, 0f, score)
                }
                .sortedByDescending { it.totalScore }
                .take(topK)
        }

        // 2. 向量召回 top-20
        val queryVector = embeddingModel.embed(query)
        val vectorHits = vectorStore.search(
            queryEmbedding = queryVector,
            topK = 20,
            track = MemoryEmbeddingEntity.TRACK_USER
        )

        val candidatePairs: List<Pair<UserMemoryEntity, Float>> = if (vectorHits.isNotEmpty()) {
            vectorHits.mapNotNull { hit ->
                val entity = userMemoryDao.getById(hit.memoryId)
                if (entity != null && (entity.status == UserMemoryEntity.STATUS_ACTIVE || entity.status == UserMemoryEntity.STATUS_CORE)) {
                    entity to hit.similarity
                } else {
                    null
                }
            }
        } else {
            // 向量库为空或尚未索引时的降级：取 active 记忆
            val allActive = userMemoryDao.getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 20)
            allActive.map { it to 0f }
        }

        if (candidatePairs.isEmpty()) return emptyList()

        // 3. 调用既有 MemoryScorer 进行六维精排，并融合语义向量相似度
        val scoredList = candidatePairs.map { (mem, vecSim) ->
            val scorerScore = MemoryScorer.calculateScore(
                memory = mem,
                query = query,
                nowTs = nowTs,
                moodValence = moodValence
            )
            val combinedScore = scorerScore + (vecSim * vectorWeight)
            ScoredMemory(
                memory = mem,
                totalScore = combinedScore,
                vectorSimilarity = vecSim,
                scorerScore = scorerScore
            )
        }

        // 4. 按综合得分从高到低排序，输出前 topK
        return scoredList
            .sortedByDescending { it.totalScore }
            .take(topK)
    }

    /**
     * 单条记忆向量索引写入
     */
    suspend fun indexMemory(memory: UserMemoryEntity) {
        val text = buildEmbeddingText(memory.content, memory.category)
        val vector = embeddingModel.embed(text)
        vectorStore.upsert(memory.id, MemoryEmbeddingEntity.TRACK_USER, vector)
    }

    /**
     * 批量记忆向量索引写入
     */
    suspend fun indexMemories(memories: List<UserMemoryEntity>) {
        if (memories.isEmpty()) return
        val items = memories.map { mem ->
            val text = buildEmbeddingText(mem.content, mem.category)
            val vector = embeddingModel.embed(text)
            Triple(mem.id, MemoryEmbeddingEntity.TRACK_USER, vector)
        }
        vectorStore.upsertBatch(items)
    }

    /**
     * 入梦流水线末端全量向量重算
     */
    suspend fun recomputeAllVectors() {
        val activeMemories = userMemoryDao.getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 5000)
        indexMemories(activeMemories)
    }

    private fun buildEmbeddingText(content: String, category: String): String {
        return if (category.isNotBlank() && category != "其他") {
            "$content ($category)"
        } else {
            content
        }
    }
}
