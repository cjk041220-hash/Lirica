package com.xiaoxi.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
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

    @Query("SELECT * FROM unspoken_thoughts WHERE status = :status ORDER BY urgency DESC, created_at DESC LIMIT :limit")
    fun getPendingThoughts(
        status: String = UnspokenThoughtEntity.STATUS_PENDING,
        limit: Int = 20
    ): Flow<List<UnspokenThoughtEntity>>

    @Query("SELECT * FROM unspoken_thoughts WHERE status = :status ORDER BY urgency DESC, created_at DESC LIMIT :limit")
    suspend fun getTopUrgentPendingThoughts(
        status: String = UnspokenThoughtEntity.STATUS_PENDING,
        limit: Int = 5
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
