package com.xiaoxi.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自主闹钟与日程约定实体 (Track C - Scheduled Alarms)
 */
@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "trigger_at")
    val triggerAt: Long,

    @ColumnInfo(name = "bot_note")
    val botNote: String,

    @ColumnInfo(name = "block_delivery")
    val blockDelivery: Boolean = false,

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_TRIGGERED = "triggered"
        const val STATUS_CANCELLED = "cancelled"
    }
}
