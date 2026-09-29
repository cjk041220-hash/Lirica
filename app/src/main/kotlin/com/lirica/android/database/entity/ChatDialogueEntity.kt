package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话多轮历史持久化实体（Chat Dialogue Archive）
 *
 * 记录多轮会话日志，支持角色（user, assistant, system）、类型（chat 普通对话, solo 独处碎碎念, event 事件）、
 * Token 统计与元数据持久化。
 */
@Entity(
    tableName = "chat_dialogues",
    indices = [
        Index(value = ["session_id"], name = "idx_dialogue_session"),
        Index(value = ["session_id", "timestamp"], name = "idx_dialogue_session_time")
    ]
)
data class ChatDialogueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "msg_type")
    val msgType: String = MSG_TYPE_CHAT,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "token_count")
    val tokenCount: Int = 0,

    @ColumnInfo(name = "model")
    val model: String? = null,

    @ColumnInfo(name = "metadata_json")
    val metadataJson: String? = null
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"

        const val MSG_TYPE_CHAT = "chat"
        const val MSG_TYPE_SOLO = "solo"
        const val MSG_TYPE_EVENT = "event"
    }
}
