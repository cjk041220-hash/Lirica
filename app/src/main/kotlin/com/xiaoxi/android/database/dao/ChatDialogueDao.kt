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

    // 注意：必须用子查询先按时间倒序取"最新 limit 条"，再正序输出。
    // 若直接 ORDER BY timestamp ASC + LIMIT，会话超过 limit 条后永远只加载最旧的一批，新消息永久丢失。
    @Query(
        "SELECT * FROM (" +
            "SELECT * FROM chat_dialogues WHERE session_id = :sessionId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit" +
            ") ORDER BY timestamp ASC, id ASC"
    )
    suspend fun getRecentBySession(sessionId: String, limit: Int = 50): List<ChatDialogueEntity>

    @Query(
        "SELECT * FROM (" +
            "SELECT * FROM chat_dialogues WHERE session_id = :sessionId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit" +
            ") ORDER BY timestamp ASC, id ASC"
    )
    fun getRecentBySessionFlow(sessionId: String, limit: Int = 50): Flow<List<ChatDialogueEntity>>

    @Query("SELECT COUNT(*) FROM chat_dialogues WHERE session_id = :sessionId")
    suspend fun getSessionMessageCount(sessionId: String): Int

    @Query("DELETE FROM chat_dialogues WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT DISTINCT session_id FROM chat_dialogues ORDER BY session_id ASC")
    suspend fun getAllSessions(): List<String>

    /**
     * 近 N 天内用户消息的时间戳（升序，跨全部会话，不含 beforeMs 那条）。
     *
     * 只取时间戳不取内容：关心检测只需要"什么时候在"，不需要"说了什么"——
     * 把读取面压到最小是隐私与 IO 的双重考虑。
     *
     * @param beforeMs 排除当前正在发送的这条消息：调用方在持久化当前消息
     * **之后**才做检测，不排除的话"距上一条消息的间隔"永远是 0。
     */
    @Query(
        "SELECT timestamp FROM chat_dialogues " +
            "WHERE role = 'user' AND timestamp >= :sinceMs AND timestamp < :beforeMs " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getUserTimestampsSince(sinceMs: Long, beforeMs: Long): List<Long>

    /** beforeMs 之前最近一条用户消息的时间（跨全部会话）；无历史时返回 null */
    @Query("SELECT MAX(timestamp) FROM chat_dialogues WHERE role = 'user' AND timestamp < :beforeMs")
    suspend fun getLastUserTimestampBefore(beforeMs: Long): Long?
}
