package com.xiaoxi.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 自叙轨经历访问接口（SelfExperienceDao）
 */
@Dao
interface SelfExperienceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SelfExperienceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SelfExperienceEntity>)

    @Update
    suspend fun update(entity: SelfExperienceEntity)

    @Delete
    suspend fun delete(entity: SelfExperienceEntity)

    @Query("DELETE FROM self_experiences WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM self_experiences WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SelfExperienceEntity?

    @Query("SELECT * FROM self_experiences ORDER BY created_at DESC LIMIT :limit")
    fun getRecentExperiences(limit: Int = 20): Flow<List<SelfExperienceEntity>>

    @Query("SELECT * FROM self_experiences WHERE status = :status ORDER BY created_at DESC LIMIT :limit")
    suspend fun getUnsharedExperiences(
        status: String = SelfExperienceEntity.STATUS_FRESH,
        limit: Int = 10
    ): List<SelfExperienceEntity>

    @Query("SELECT * FROM self_experiences WHERE status != :excludeStatus ORDER BY created_at DESC LIMIT :limit")
    suspend fun getActiveExperiences(
        excludeStatus: String = SelfExperienceEntity.STATUS_ARCHIVED,
        limit: Int = 20
    ): List<SelfExperienceEntity>

    @Query("""
        UPDATE self_experiences
        SET status = :sharedStatus,
            access_count = access_count + 1,
            last_shared_at = :sharedAt
        WHERE id = :id
    """)
    suspend fun markAsShared(
        id: String,
        sharedAt: Long,
        sharedStatus: String = SelfExperienceEntity.STATUS_SHARED
    )

    @Query("SELECT * FROM self_experiences WHERE domain_tag = :domainTag ORDER BY created_at DESC")
    suspend fun searchByDomain(domainTag: String): List<SelfExperienceEntity>
}
