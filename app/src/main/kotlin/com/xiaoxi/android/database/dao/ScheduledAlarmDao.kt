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

    /**
     * 未来窗口 (nowMs, untilMs] 内待触发的日程（list_events 生活工具用）。
     * 只取时间戳窗口与条数上限，避免"列出全部历史"式查询在长期使用后无限膨胀。
     */
    @Query(
        "SELECT * FROM scheduled_alarms WHERE status = 'pending' AND trigger_at > :nowMs AND trigger_at <= :untilMs " +
            "ORDER BY trigger_at ASC LIMIT :limit"
    )
    suspend fun getUpcomingAlarms(nowMs: Long, untilMs: Long, limit: Int): List<ScheduledAlarmEntity>

    @Query("UPDATE scheduled_alarms SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
