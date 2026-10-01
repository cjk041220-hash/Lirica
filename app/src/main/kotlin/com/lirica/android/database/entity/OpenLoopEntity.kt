package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 开环记忆实体（Open Loop Entity）
 *
 * 追踪对话中提及的未决事务（如“明天下午面试”、“周五大作业答辩”）。
 * 具备完整的关怀与核销生命周期：OPEN -> TRACKING -> TRIGGERED -> RESOLVED / EXPIRED。
 */
@Entity(
    tableName = "open_loops",
    indices = [
        Index(value = ["status"], name = "idx_open_loops_status"),
        Index(value = ["target_time_ms"], name = "idx_open_loops_target_time")
    ]
)
data class OpenLoopEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "source_memory_id")
    val sourceMemoryId: String? = null,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "target_time_ms")
    val targetTimeMs: Long,

    @ColumnInfo(name = "trigger_window_start_ms")
    val triggerWindowStartMs: Long,

    @ColumnInfo(name = "status")
    val status: String = STATUS_OPEN,

    @ColumnInfo(name = "urgency_level")
    val urgencyLevel: Int = 1,

    @ColumnInfo(name = "follow_up_prompt")
    val followUpPrompt: String,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_prompted_ms")
    val lastPromptedMs: Long = 0L,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_TRACKING = "TRACKING"
        const val STATUS_TRIGGERED = "TRIGGERED"
        const val STATUS_RESOLVED = "RESOLVED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
