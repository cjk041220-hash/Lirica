package com.xiaoxi.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity

/**
 * 自主闹钟与日程约定表访问接口（ScheduledAlarmDao）
 */
@Dao
interface ScheduledAlarmDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScheduledAlarmEntity): Long

    @Update
    suspend fun update(entity: ScheduledAlarmEntity)

    @Delete
    suspend fun delete(entity: ScheduledAlarmEntity)

    @Query("DELETE FROM scheduled_alarms WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM scheduled_alarms WHERE status = 'pending' AND trigger_at <= :nowMs ORDER BY trigger_at ASC")
    suspend fun getPendingAlarms(nowMs: Long): List<ScheduledAlarmEntity>

    @Query("UPDATE scheduled_alarms SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
