package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自叙轨经历实体（Track B - Self-Experiences & Thoughts）
 *
 * 记录 Agent 自主上网阅读、见闻感知、日记感悟的第一人称心得与经历。
 * 区别于传统的“只记用户”模式，Lirica 拥有自己的独立经历库，作为对话时主动分享与共鸣的活水。
 */
@Entity(
    tableName = "self_experiences",
    indices = [
        Index(value = ["domain_tag"], name = "idx_self_exp_domain"),
        Index(value = ["created_at"], name = "idx_self_exp_created_at"),
        Index(value = ["status"], name = "idx_self_exp_status")
    ]
)
data class SelfExperienceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "source_url")
    val sourceUrl: String? = null,

    @ColumnInfo(name = "domain_tag")
    val domainTag: String = "日常感悟",

    @ColumnInfo(name = "emotional_insight")
    val emotionalInsight: String? = null,

    @ColumnInfo(name = "valence")
    val valence: Float = 0.0f,

    @ColumnInfo(name = "arousal")
    val arousal: Float = 0.0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,

    @ColumnInfo(name = "last_shared_at")
    val lastSharedAt: Long? = null,

    @ColumnInfo(name = "status")
    val status: String = STATUS_FRESH,

    @ColumnInfo(name = "is_placeholder", defaultValue = "0")
    val isPlaceholder: Boolean = false
) {
    companion object {
        const val STATUS_FRESH = "fresh"
        const val STATUS_SHARED = "shared"
        const val STATUS_ARCHIVED = "archived"
    }
}
