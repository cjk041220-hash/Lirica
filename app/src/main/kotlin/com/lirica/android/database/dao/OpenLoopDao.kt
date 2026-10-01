package com.lirica.android.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lirica.android.database.entity.OpenLoopEntity

/**
 * 开环记忆数据访问接口（OpenLoopDao）
 */
@Dao
interface OpenLoopDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OpenLoopEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<OpenLoopEntity>)

    @Update
    suspend fun update(entity: OpenLoopEntity)

    @Query("SELECT * FROM open_loops WHERE id = :id")
    suspend fun getById(id: String): OpenLoopEntity?

    @Query("SELECT * FROM open_loops WHERE status = :status ORDER BY target_time_ms ASC")
    suspend fun getByStatus(status: String): List<OpenLoopEntity>

    @Query("SELECT * FROM open_loops WHERE status IN ('OPEN', 'TRACKING') AND trigger_window_start_ms <= :currentTimeMs ORDER BY target_time_ms ASC")
    suspend fun getDueForTracking(currentTimeMs: Long): List<OpenLoopEntity>

    @Query("UPDATE open_loops SET status = :newStatus WHERE id = :id")
    suspend fun updateStatus(id: String, newStatus: String)

    @Query("UPDATE open_loops SET status = :newStatus, last_prompted_ms = :promptedMs, retry_count = retry_count + 1 WHERE id = :id")
    suspend fun markPrompted(id: String, promptedMs: Long, newStatus: String = OpenLoopEntity.STATUS_TRIGGERED)

    @Query("UPDATE open_loops SET status = 'EXPIRED' WHERE status IN ('OPEN', 'TRACKING', 'TRIGGERED') AND target_time_ms < :expiryThresholdMs")
    suspend fun expireOldLoops(expiryThresholdMs: Long): Int

    @Query("DELETE FROM open_loops WHERE status IN ('RESOLVED', 'EXPIRED') AND last_prompted_ms < :beforeMs")
    suspend fun cleanupClosedLoops(beforeMs: Long): Int
}
