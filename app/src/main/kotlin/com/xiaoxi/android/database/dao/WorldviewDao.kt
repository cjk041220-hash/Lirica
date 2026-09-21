package com.xiaoxi.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xiaoxi.android.database.entity.WorldviewEntity

/**
 * 高层世界观认知表访问接口（WorldviewDao）
 */
@Dao
interface WorldviewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WorldviewEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WorldviewEntity>)

    @Update
    suspend fun update(entity: WorldviewEntity)

    @Delete
    suspend fun delete(entity: WorldviewEntity)

    @Query("DELETE FROM worldview_entities WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM worldview_entities ORDER BY supporting_evidence_count DESC, last_confirmed_at DESC")
    suspend fun getAll(): List<WorldviewEntity>

    @Query("SELECT * FROM worldview_entities WHERE category = :category ORDER BY supporting_evidence_count DESC")
    suspend fun getByCategory(category: String): List<WorldviewEntity>

    @Query("SELECT * FROM worldview_entities ORDER BY supporting_evidence_count DESC, confidence DESC LIMIT :limit")
    suspend fun getTopConfirmed(limit: Int = 10): List<WorldviewEntity>
}
