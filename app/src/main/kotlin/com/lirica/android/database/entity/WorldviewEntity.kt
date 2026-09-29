package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 高层世界观认知实体 (Track A 顶级提炼 - Worldview Entity)
 *
 * 记录在不同日期、多个独立情境下反复得到验证的深层规律。
 * 包含生活习惯、人际边界、工作哲学与偏好体系等高维认知。
 */
@Entity(tableName = "worldview_entities")
data class WorldviewEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "statement")
    val statement: String,

    @ColumnInfo(name = "supporting_evidence_count")
    val supportingEvidenceCount: Int = 1,

    @ColumnInfo(name = "last_confirmed_at")
    val lastConfirmedAt: Long,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f
)
