package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 情境感知切片实体（Episodic Sensing Slice）
 *
 * 记录用户白天使用手机过程中的情境驻留块（如在自习类应用停留 45 分钟、戴耳机听歌、深夜亮屏）。
 * 用于夜间自叙梦系统（SubconsciousDreamCoordinator）消化并提炼为 Track B 自叙经历。
 */
@Entity(
    tableName = "episodic_sensing_slices",
    indices = [
        Index(value = ["timestamp_ms"], name = "idx_sensing_slices_timestamp"),
        Index(value = ["is_consolidated"], name = "idx_sensing_slices_consolidated")
    ]
)
data class EpisodicSensingSliceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,

    @ColumnInfo(name = "dwell_duration_ms")
    val dwellDurationMs: Long,

    @ColumnInfo(name = "app_category")
    val appCategory: String,

    @ColumnInfo(name = "primary_package")
    val primaryPackage: String,

    @ColumnInfo(name = "summary_scene")
    val summaryScene: String,

    @ColumnInfo(name = "hardware_context_json")
    val hardwareContextJson: String = "{}",

    @ColumnInfo(name = "is_consolidated")
    val isConsolidated: Boolean = false
) {
    companion object {
        const val CATEGORY_STUDY = "STUDY"
        const val CATEGORY_ENTERTAINMENT = "ENTERTAINMENT"
        const val CATEGORY_COMMUNICATION = "COMMUNICATION"
        const val CATEGORY_SYSTEM = "SYSTEM"
        const val CATEGORY_LATE_NIGHT = "LATE_NIGHT"
    }
}
