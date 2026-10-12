package com.lirica.android.memory.retrieval

import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.memory.MemoryEmbeddingEntity
import com.lirica.android.database.memory.MemoryScorer
import com.lirica.android.database.memory.MemoryVectorStore

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
 *    同时支持 Track A (用户记忆) 与 Track B (自叙经历) 双轨检索。
 *
 * 2. 认知精排 (Cognitive Re-ranking)：
 *    对 top-20 候选实体调用既有 MemoryScorer 计算六维得分
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
    private val vectorWeight: Float = 1.0f,
    private val selfExperienceDao: SelfExperienceDao? = null
) {

    data class ScoredMemory(
        val memory: UserMemoryEntity,
        val totalScore: Float,
        val vectorSimilarity: Float,
        val scorerScore: Float
    )

    data class ScoredSelfExperience(
        val experience: SelfExperienceEntity,
        val vectorSimilarity: Float
    )

    /**
     * 核心融合检索接口：向量召回 top-20 → MemoryScorer 六维重排 → 返回 top-N (Track A 用户轨)
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
     * 自叙轨经历语义向量召回 (Track B)
     */
    suspend fun retrieveSelfExperiences(
        query: String,
        limit: Int = 2,
        unsharedOnly: Boolean = false
    ): List<SelfExperienceEntity> {
        if (limit <= 0) return emptyList()
        val dao = selfExperienceDao ?: return emptyList()

        if (query.isBlank()) {
            return if (unsharedOnly) {
                dao.getUnsharedExperiences(limit = limit)
            } else {
                dao.getActiveExperiences(limit = limit)
            }
        }

        // 向量召回 Track B top 候选
        val queryVector = embeddingModel.embed(query)
        val vectorHits = vectorStore.search(
            queryEmbedding = queryVector,
            topK = limit * 3,
            track = MemoryEmbeddingEntity.TRACK_SELF
        )

        val hits = if (vectorHits.isNotEmpty()) {
            vectorHits.mapNotNull { hit ->
                val entity = dao.getById(hit.memoryId)
                if (entity != null && entity.status != SelfExperienceEntity.STATUS_ARCHIVED) {
                    if (!unsharedOnly || entity.status == SelfExperienceEntity.STATUS_FRESH) {
                        entity to hit.similarity
                    } else null
                } else null
            }
        } else emptyList()

        // 命中且相似度有效时优先按相似度返回，若不足则从数据库补充最新未分享经历
        val result = hits.sortedByDescending { it.second }.map { it.first }.take(limit).toMutableList()
        if (result.size < limit) {
            val existingIds = result.map { it.id }.toSet()
            val fallback = if (unsharedOnly) {
                dao.getUnsharedExperiences(limit = limit * 2)
            } else {
                dao.getActiveExperiences(limit = limit * 2)
            }
            for (item in fallback) {
                if (item.id !in existingIds) {
                    result.add(item)
                    if (result.size >= limit) break
                }
            }
        }
        return result
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
            val allActive = userMemoryDao.getActiveAndCoreList(limit = 200)
            return allActive
                .map { mem ->
                    val score = MemoryScorer.calculateScore(mem, "", nowTs, moodValence)
                    ScoredMemory(mem, score, 0f, score)
                }
                .sortedByDescending { it.totalScore }
                .take(topK)
        }

        // 2. 真混合检索候选池构建 (Dense Vector + Sparse Keyword / Core Profile 并集)
        val queryVector = embeddingModel.embed(query)
        val vectorHits = vectorStore.search(
            queryEmbedding = queryVector,
            topK = 20,
            track = MemoryEmbeddingEntity.TRACK_USER
        )

        val vectorSimMap = vectorHits.associate { it.memoryId to it.similarity }
        val activeAndCoreMemories = userMemoryDao.getActiveAndCoreList(limit = 200)
        val memoryMap = activeAndCoreMemories.associateBy { it.id }

        val candidatePairsMap = LinkedHashMap<String, Pair<UserMemoryEntity, Float>>()
        for (hit in vectorHits) {
            val entity = memoryMap[hit.memoryId] ?: userMemoryDao.getById(hit.memoryId)
            if (entity != null && (entity.status == UserMemoryEntity.STATUS_ACTIVE || entity.status == UserMemoryEntity.STATUS_CORE)) {
                candidatePairsMap[entity.id] = entity to hit.similarity
            }
        }

        for (mem in activeAndCoreMemories) {
            if (!candidatePairsMap.containsKey(mem.id)) {
                val hasOverlap = MemoryScorer.bigramOverlap(query, "${mem.content} ${mem.category}") > 0f
                val isCore = mem.status == UserMemoryEntity.STATUS_CORE
                if (hasOverlap || isCore) {
                    val sim = vectorSimMap[mem.id] ?: 0f
                    candidatePairsMap[mem.id] = mem to sim
                }
            }
        }

        val candidatePairs: List<Pair<UserMemoryEntity, Float>> = if (candidatePairsMap.isNotEmpty()) {
            candidatePairsMap.values.toList()
        } else {
            activeAndCoreMemories.take(20).map { it to 0f }
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
     * 单条用户记忆向量索引写入
     */
    suspend fun indexMemory(memory: UserMemoryEntity) {
        val text = buildEmbeddingText(memory.content, memory.category)
        val vector = embeddingModel.embed(text)
        vectorStore.upsert(memory.id, MemoryEmbeddingEntity.TRACK_USER, vector)
    }

    /**
     * 批量用户记忆向量索引写入
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
     * 单条自叙经历向量索引写入 (Track B)
     */
    suspend fun indexSelfExperience(experience: SelfExperienceEntity) {
        val text = buildSelfEmbeddingText(experience.title, experience.content, experience.domainTag)
        val vector = embeddingModel.embed(text)
        vectorStore.upsert(experience.id, MemoryEmbeddingEntity.TRACK_SELF, vector)
    }

    /**
     * 批量自叙经历向量索引写入 (Track B)
     */
    suspend fun indexSelfExperiences(experiences: List<SelfExperienceEntity>) {
        if (experiences.isEmpty()) return
        val items = experiences.map { exp ->
            val text = buildSelfEmbeddingText(exp.title, exp.content, exp.domainTag)
            val vector = embeddingModel.embed(text)
            Triple(exp.id, MemoryEmbeddingEntity.TRACK_SELF, vector)
        }
        vectorStore.upsertBatch(items)
    }

    /**
     * 删除记忆向量索引（防止已失效/删除的记忆成为孤儿向量）
     */
    suspend fun removeVector(memoryId: String) {
        vectorStore.delete(memoryId)
    }

    /**
     * 入梦流水线末端全量向量重算与双轨索引刷新
     */
    suspend fun recomputeAllVectors() {
        // 1. 刷新 Track A 活跃记忆
        val activeMemories = userMemoryDao.getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 5000)
        indexMemories(activeMemories)

        // 2. 刷新 Track B 活跃自叙经历
        selfExperienceDao?.let { dao ->
            val activeExperiences = dao.getActiveExperiences(limit = 1000)
            indexSelfExperiences(activeExperiences)
        }
    }

    private fun buildEmbeddingText(content: String, category: String): String {
        return if (category.isNotBlank() && category != "其他") {
            "$content ($category)"
        } else {
            content
        }
    }

    private fun buildSelfEmbeddingText(title: String, content: String, domainTag: String): String {
        val tagPrefix = if (domainTag.isNotBlank() && domainTag != "日常感悟") "[$domainTag] " else ""
        val titlePrefix = if (title.isNotBlank()) "$title: " else ""
        return "$tagPrefix$titlePrefix$content"
    }
}
