package com.xiaoxi.android.database.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 记忆向量数据访问对象 (Memory Embedding DAO)
 */
@Dao
interface MemoryEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: MemoryEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(entities: List<MemoryEmbeddingEntity>)

    @Query("SELECT * FROM memory_embeddings WHERE memory_id = :memoryId")
    suspend fun getByMemoryId(memoryId: String): MemoryEmbeddingEntity?

    @Query("SELECT * FROM memory_embeddings WHERE track = :track")
    suspend fun getByTrack(track: String): List<MemoryEmbeddingEntity>

    @Query("SELECT * FROM memory_embeddings")
    suspend fun getAll(): List<MemoryEmbeddingEntity>

    @Query("DELETE FROM memory_embeddings WHERE memory_id = :memoryId")
    suspend fun deleteByMemoryId(memoryId: String)

    @Query("DELETE FROM memory_embeddings")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM memory_embeddings")
    suspend fun count(): Int
}
