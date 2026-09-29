package com.lirica.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lirica.android.database.entity.UserMemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 用户轨记忆访问接口（UserMemoryDao）
 */
@Dao
interface UserMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UserMemoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<UserMemoryEntity>)

    @Update
    suspend fun update(entity: UserMemoryEntity)

    @Delete
    suspend fun delete(entity: UserMemoryEntity)

    @Query("DELETE FROM user_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM user_memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserMemoryEntity?

    @Query("SELECT * FROM user_memories WHERE status = :status ORDER BY last_mention_ts DESC LIMIT :limit")
    fun getAllActive(
        status: String = UserMemoryEntity.STATUS_ACTIVE,
        limit: Int = 100
    ): Flow<List<UserMemoryEntity>>

    @Query("SELECT * FROM user_memories WHERE status = :status ORDER BY last_mention_ts DESC LIMIT :limit")
    suspend fun getActiveList(
        status: String = UserMemoryEntity.STATUS_ACTIVE,
        limit: Int = 200
    ): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE category = :category AND status = :status ORDER BY last_mention_ts DESC")
    suspend fun getByCategory(
        category: String,
        status: String = UserMemoryEntity.STATUS_ACTIVE
    ): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE content LIKE '%' || :keyword || '%' AND status = :status ORDER BY last_mention_ts DESC")
    suspend fun searchByContent(
        keyword: String,
        status: String = UserMemoryEntity.STATUS_ACTIVE
    ): List<UserMemoryEntity>

    /**
     * 矛盾记忆失效与覆盖操作
     */
    @Query("""
        UPDATE user_memories
        SET status = :supersededStatus,
            superseded_by = :newId,
            valid_to = :supersededTimestamp,
            prev_content = :prevContentSnapshot
        WHERE id = :oldId
    """)
    suspend fun markSuperseded(
        oldId: String,
        newId: String,
        supersededTimestamp: Long,
        prevContentSnapshot: String?,
        supersededStatus: String = UserMemoryEntity.STATUS_SUPERSEDED
    )

    /**
     * 按日去重 hitCount：仅当 last_hit_date != todayDate 时，hitCount + 1 并刷新 last_hit_date 与 last_mention_ts
     */
    @Query("""
        UPDATE user_memories
        SET hit_count = hit_count + 1,
            last_hit_date = :todayDate,
            last_mention_ts = :hitTimestamp
        WHERE id = :id AND (last_hit_date IS NULL OR last_hit_date != :todayDate)
    """)
    suspend fun updateHitCountIfNewDay(
        id: String,
        todayDate: String,
        hitTimestamp: Long
    )

    /**
     * 主动想起计步与使用时间戳更新
     */
    @Query("""
        UPDATE user_memories
        SET access_count = access_count + 1,
            last_used_at = :usedAt
        WHERE id = :id
    """)
    suspend fun incrementAccessCount(
        id: String,
        usedAt: Long
    )
}
