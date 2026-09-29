package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户轨记忆实体（Track A - User Profile & Facts）
 *
 * 记录关于用户的画像、偏好、作息、生活习惯、人际关系与事实。
 * 支持双时间戳时序跟踪（validFrom, validTo）与矛盾覆盖（status='superseded', supersededBy, prevContent）。
 */
@Entity(
    tableName = "user_memories",
    indices = [
        Index(value = ["status"], name = "idx_user_memories_status"),
        Index(value = ["category", "status"], name = "idx_user_memories_category_status")
    ]
)
data class UserMemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "category")
    val category: String = "其他",

    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f,

    @ColumnInfo(name = "importance")
    val importance: Int = 5,

    @ColumnInfo(name = "valence")
    val valence: Float = 0.0f,

    @ColumnInfo(name = "arousal")
    val arousal: Float = 0.0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_mention_ts")
    val lastMentionTs: Long = createdAt,

    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,

    @ColumnInfo(name = "hit_count")
    val hitCount: Int = 0,

    @ColumnInfo(name = "last_hit_date")
    val lastHitDate: String? = null,

    @ColumnInfo(name = "status")
    val status: String = STATUS_ACTIVE,

    @ColumnInfo(name = "superseded_by")
    val supersededBy: String? = null,

    @ColumnInfo(name = "prev_content")
    val prevContent: String? = null,

    @ColumnInfo(name = "source")
    val source: String? = null,

    @ColumnInfo(name = "valid_from")
    val validFrom: Long = createdAt,

    @ColumnInfo(name = "valid_to")
    val validTo: Long? = null
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUPERSEDED = "superseded"
        const val STATUS_ARCHIVED = "archived"
        const val STATUS_CORE = "core"
    }
}
