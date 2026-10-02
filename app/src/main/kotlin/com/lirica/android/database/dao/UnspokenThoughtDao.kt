package com.lirica.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lirica.android.database.entity.UnspokenThoughtEntity
import kotlinx.coroutines.flow.Flow

/**
 * 未完成念头池访问接口（UnspokenThoughtDao）
 */
@Dao
interface UnspokenThoughtDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UnspokenThoughtEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<UnspokenThoughtEntity>)

    @Update
    suspend fun update(entity: UnspokenThoughtEntity)

    @Delete
    suspend fun delete(entity: UnspokenThoughtEntity)

    @Query("DELETE FROM unspoken_thoughts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM unspoken_thoughts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UnspokenThoughtEntity?

    /**
     * 按触发上下文前缀取念头（含已过期但尚未清理的）。
     * 用于开环闭环时撤回对应念头：过期条目也已经不在 getTopUrgentPendingThoughts 的返回里，
     * 但把它们留在池子里会污染"念头池攒了多少没说出口的话"这类统计。
     */
    @Query("SELECT * FROM unspoken_thoughts WHERE status = :status AND trigger_context LIKE :prefix || '%'")
    suspend fun getByTriggerContextPrefix(
        prefix: String,
        status: String = UnspokenThoughtEntity.STATUS_PENDING
    ): List<UnspokenThoughtEntity>

    @Query("""
        SELECT * FROM unspoken_thoughts
        WHERE status = :status
          AND (expiry_time IS NULL OR expiry_time > :nowMs)
        ORDER BY urgency DESC, created_at DESC LIMIT :limit
    """)
    fun getPendingThoughts(
        status: String = UnspokenThoughtEntity.STATUS_PENDING,
        limit: Int = 20,
        nowMs: Long
    ): Flow<List<UnspokenThoughtEntity>>

    /**
     * 取出紧急度最高的待表达念头。
     *
     * 注意 expiry_time 过滤**必须在查询层**：主动裁决、破冰展示、L3 通知三条取数路径都走这里，
     * 只靠刷新时的批量 discardExpiredThoughts 会漏掉"没人来刷新"的时间窗，
     * 导致已经过期的事项（考完的考试、开完的会）继续被当成高优先念头递到用户面前。
     */
    @Query("""
        SELECT * FROM unspoken_thoughts
        WHERE status = :status
          AND (expiry_time IS NULL OR expiry_time > :nowMs)
        ORDER BY urgency DESC, created_at DESC LIMIT :limit
    """)
    suspend fun getTopUrgentPendingThoughts(
        status: String = UnspokenThoughtEntity.STATUS_PENDING,
        limit: Int = 5,
        nowMs: Long
    ): List<UnspokenThoughtEntity>

    @Query("""
        UPDATE unspoken_thoughts
        SET status = :consumedStatus,
            consumed_at = :consumedAt
        WHERE id = :id
    """)
    suspend fun markConsumed(
        id: String,
        consumedAt: Long,
        consumedStatus: String = UnspokenThoughtEntity.STATUS_CONSUMED
    )

    @Query("""
        UPDATE unspoken_thoughts
        SET status = :discardedStatus
        WHERE id = :id
    """)
    suspend fun markDiscarded(
        id: String,
        discardedStatus: String = UnspokenThoughtEntity.STATUS_DISCARDED
    )

    @Query("""
        UPDATE unspoken_thoughts
        SET status = :discardedStatus
        WHERE status = :pendingStatus
          AND expiry_time IS NOT NULL
          AND expiry_time < :currentTimestamp
    """)
    suspend fun discardExpiredThoughts(
        currentTimestamp: Long,
        pendingStatus: String = UnspokenThoughtEntity.STATUS_PENDING,
        discardedStatus: String = UnspokenThoughtEntity.STATUS_DISCARDED
    ): Int

    @Query("""
        UPDATE unspoken_thoughts
        SET decay_score = :newScore
        WHERE id = :id
    """)
    suspend fun updateDecayScore(
        id: String,
        newScore: Float
    )
}
