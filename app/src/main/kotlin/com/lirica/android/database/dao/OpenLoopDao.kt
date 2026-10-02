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

    /**
     * 取出全部**尚未闭环**的事项：OPEN / TRACKING / TRIGGERED。
     *
     * TRIGGERED 必须包含在内：那个状态只表示"已经提醒过"，事情本身还没结束——
     * 用户这时候说"我做完了"，正是最该闭嘴的时刻；漏掉它就会出现
     * "她刚提醒完、你回一句'早做完了'，她过会儿还来问"。
     *
     * 注意匹配方向：判断"用户这句话里有没有提到某个事项"，必须拿**事项标题去用户文本里找**，
     * 而不是拿整句用户消息去 LIKE 标题（后者永远匹配不上长句）。
     */
    @Query("""
        SELECT * FROM open_loops
        WHERE status IN ('OPEN', 'TRACKING', 'TRIGGERED')
        ORDER BY target_time_ms ASC
        LIMIT :limit
    """)
    suspend fun getOpenLoops(limit: Int = 50): List<OpenLoopEntity>

    @Query("SELECT * FROM open_loops WHERE status IN ('OPEN', 'TRACKING') AND trigger_window_start_ms <= :currentTimeMs ORDER BY target_time_ms ASC")
    suspend fun getDueForTracking(currentTimeMs: Long): List<OpenLoopEntity>

    /**
     * 按归一化标题查找仍活跃（未闭环）的开环事项。
     * 供记忆抽取器建环前去重：用户隔几轮重提同一事项时刷新既有环而非重复建环。
     */
    @Query("""
        SELECT * FROM open_loops
        WHERE status IN ('OPEN', 'TRACKING', 'TRIGGERED') AND title = :title
        ORDER BY target_time_ms ASC
        LIMIT 1
    """)
    suspend fun getActiveByTitle(title: String): OpenLoopEntity?

    @Query("UPDATE open_loops SET status = :newStatus WHERE id = :id")
    suspend fun updateStatus(id: String, newStatus: String)

    /**
     * 条件更新：仅当事项仍处于可提醒状态（OPEN/TRACKING）时置为 [newStatus] 并累计提醒次数。
     * @return 受影响行数：1 = 占坑成功（本次负责投递念头）；0 = 已被并发路径处理，调用方必须跳过注入。
     * 这是"先占坑再注入"幂等模式的核心：正确性不再依赖调用方持有外部状态锁。
     */
    @Query("""
        UPDATE open_loops
        SET status = :newStatus, last_prompted_ms = :promptedMs, retry_count = retry_count + 1
        WHERE id = :id AND status IN ('OPEN', 'TRACKING')
    """)
    suspend fun markPromptedIfActive(id: String, promptedMs: Long, newStatus: String = OpenLoopEntity.STATUS_TRIGGERED): Int

    @Query("UPDATE open_loops SET status = 'EXPIRED' WHERE status IN ('OPEN', 'TRACKING', 'TRIGGERED') AND target_time_ms < :expiryThresholdMs")
    suspend fun expireOldLoops(expiryThresholdMs: Long): Int

    @Query("DELETE FROM open_loops WHERE status IN ('RESOLVED', 'EXPIRED') AND last_prompted_ms < :beforeMs")
    suspend fun cleanupClosedLoops(beforeMs: Long): Int
}
