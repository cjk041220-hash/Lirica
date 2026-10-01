package com.lirica.android.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lirica.android.database.entity.EpisodicSensingSliceEntity

/**
 * 情境感知切片数据访问接口（EpisodicSensingSliceDao）
 */
@Dao
interface EpisodicSensingSliceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slice: EpisodicSensingSliceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slices: List<EpisodicSensingSliceEntity>)

    @Query("SELECT * FROM episodic_sensing_slices WHERE is_consolidated = 0 ORDER BY timestamp_ms ASC LIMIT :limit")
    suspend fun getUnconsolidatedSlices(limit: Int = 30): List<EpisodicSensingSliceEntity>

    @Query("UPDATE episodic_sensing_slices SET is_consolidated = 1 WHERE id IN (:ids)")
    suspend fun markAsConsolidated(ids: List<String>)

    @Query("SELECT * FROM episodic_sensing_slices WHERE timestamp_ms >= :sinceMs ORDER BY timestamp_ms DESC LIMIT :limit")
    suspend fun getRecentSlices(sinceMs: Long, limit: Int = 50): List<EpisodicSensingSliceEntity>

    @Query("SELECT COUNT(*) FROM episodic_sensing_slices WHERE is_consolidated = 0")
    suspend fun getUnconsolidatedCount(): Int

    @Query("SELECT COUNT(*) FROM episodic_sensing_slices")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM episodic_sensing_slices WHERE id IN (SELECT id FROM episodic_sensing_slices ORDER BY timestamp_ms ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Query("DELETE FROM episodic_sensing_slices WHERE is_consolidated = 1 AND timestamp_ms < :beforeMs")
    suspend fun deleteOldConsolidated(beforeMs: Long): Int
}
