package com.xiaoxi.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话多轮历史访问接口（ChatDialogueDao）
 */
@Dao
interface ChatDialogueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChatDialogueEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ChatDialogueEntity>)

    @Update
    suspend fun update(entity: ChatDialogueEntity)

    @Delete
    suspend fun delete(entity: ChatDialogueEntity)

    @Query("SELECT * FROM chat_dialogues WHERE id = :id LIMIT 1")
    suspend fun getDialogueById(id: Long): ChatDialogueEntity?

    @Query("SELECT * FROM chat_dialogues WHERE session_id = :sessionId ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getRecentBySession(sessionId: String, limit: Int = 50): List<ChatDialogueEntity>

    @Query("SELECT * FROM chat_dialogues WHERE session_id = :sessionId ORDER BY timestamp ASC, id ASC LIMIT :limit")
    fun getRecentBySessionFlow(sessionId: String, limit: Int = 50): Flow<List<ChatDialogueEntity>>

    @Query("SELECT COUNT(*) FROM chat_dialogues WHERE session_id = :sessionId")
    suspend fun getSessionMessageCount(sessionId: String): Int

    @Query("DELETE FROM chat_dialogues WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT DISTINCT session_id FROM chat_dialogues ORDER BY session_id ASC")
    suspend fun getAllSessions(): List<String>
}
