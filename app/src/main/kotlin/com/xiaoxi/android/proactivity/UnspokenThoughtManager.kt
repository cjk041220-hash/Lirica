package com.xiaoxi.android.proactivity

import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.perception.model.PerceptionSnapshot

/**
 * 未完成念头池生命周期管理者
 */
interface UnspokenThoughtManager {
    suspend fun processPerception(snapshot: PerceptionSnapshot, emotion: EmotionVector, nowMs: Long): Double
    suspend fun getTopPendingThoughts(limit: Int = 5): List<UnspokenThoughtEntity>
    suspend fun consumeThought(id: String, nowMs: Long)
    suspend fun cleanupExpired(nowMs: Long): Int
    fun markExpressed(nowMs: Long)
    fun getCurrentMotivation(nowMs: Long = System.currentTimeMillis()): Double
}

class UnspokenThoughtManagerImpl(
    private val dao: UnspokenThoughtDao,
    private val dynamics: MotivationDynamics = MotivationDynamicsImpl(),
    private val synthesizer: ThoughtSynthesizer = ThoughtSynthesizerImpl()
) : UnspokenThoughtManager {

    private var lastProcessTimeMs: Long = 0L

    override suspend fun processPerception(
        snapshot: PerceptionSnapshot,
        emotion: EmotionVector,
        nowMs: Long
    ): Double {
        val deltaTimeMs = if (lastProcessTimeMs > 0L && nowMs > lastProcessTimeMs) {
            nowMs - lastProcessTimeMs
        } else {
            0L
        }
        lastProcessTimeMs = nowMs

        // 1. 动力学带泄漏累积与更新
        val vector = dynamics.update(snapshot, emotion, deltaTimeMs)
        val motivationScore = dynamics.computeTotalMotivation(vector, nowMs)

        // 2. 尝试合成内心念头
        if (motivationScore > 0.0) {
            val thought = synthesizer.synthesize(snapshot, emotion, motivationScore)
            if (thought != null) {
                dao.insert(thought)
            }
        }

        return motivationScore
    }

    override suspend fun getTopPendingThoughts(limit: Int): List<UnspokenThoughtEntity> {
        return dao.getTopUrgentPendingThoughts(limit = limit)
    }

    override suspend fun consumeThought(id: String, nowMs: Long) {
        dao.markConsumed(id, nowMs)
    }

    override suspend fun cleanupExpired(nowMs: Long): Int {
        return dao.discardExpiredThoughts(currentTimestamp = nowMs)
    }

    override fun markExpressed(nowMs: Long) {
        dynamics.onExpressed(nowMs)
    }

    override fun getCurrentMotivation(nowMs: Long): Double {
        return dynamics.computeTotalMotivation(dynamics.getCurrentVector(), nowMs)
    }
}
