package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 未完成念头池实体（Unspoken Thoughts Pool）
 *
 * 记录 Agent 在感知或观察用户行为时产生的“想说但现在不适合说”的碎片念头。
 * 具备紧急度 (urgency)、衰减分 (decayScore)、过期时间 (expiryTime) 与状态机。
 * 为主动性裁决器与破冰交谈提供高价值事实池。
 */
@Entity(
    tableName = "unspoken_thoughts",
    indices = [
        Index(value = ["status"], name = "idx_thoughts_status"),
        Index(value = ["urgency"], name = "idx_thoughts_urgency"),
        Index(value = ["created_at"], name = "idx_thoughts_created_at")
    ]
)
data class UnspokenThoughtEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "thought")
    val thought: String,

    /**
     * 主动送达时的标题（L3 通知用）。
     * 空串表示无专属标题，L3 通道会退化为"按时间/来源生成的兜底标题"。
     * 之所以单独建模而不是从正文里拆：正文是给用户看的原话，标题是承载语气的短句，
     * 二者语义不同，拆字符串会让后续换文案时不得不改动已落库的历史数据。
     */
    @ColumnInfo(name = "content_title", defaultValue = "")
    val contentTitle: String = "",

    @ColumnInfo(name = "trigger_context")
    val triggerContext: String? = null,

    @ColumnInfo(name = "urgency")
    val urgency: Float = 0.5f,

    @ColumnInfo(name = "decay_score")
    val decayScore: Float = 1.0f,

    @ColumnInfo(name = "target_scenario")
    val targetScenario: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "expiry_time")
    val expiryTime: Long? = null,

    @ColumnInfo(name = "consumed_at")
    val consumedAt: Long? = null,

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_CONSUMED = "consumed"
        const val STATUS_DISCARDED = "discarded"
    }
}
