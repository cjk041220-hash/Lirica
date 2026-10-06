package com.lirica.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 机型自适应经验轨迹实体（主方案5.0 §5.2 · P1-2 落地 · DB v6）。
 *
 * 设计裁决（人类架构师定案 · 经验轨迹**放弃云端/预置，确立单机自学进化**）：
 * 宏在"该机型 + 该应用版本 + 该起始页"三者同时成立的条件下才可回放，
 * 因此 [targetAppVersionCode] 与 [startPageFingerprint] 是**一等公民**，
 * 不是可选的调试字段——缺了它们就是盲回放，方案明令拒绝。
 *
 * 字段与方案 §5.2 存储实体逐条对应：
 * - macroId / intentSignature / appPackage / targetAppVersionCode
 * - startPageFingerprint（起始页抗噪拓扑指纹）
 * - stepsJson（归一化坐标 + ref_id 复合动作序列）
 * - explicitSlotKeys（仅显式槽位，一期不做模糊参数推断）
 * - failureCount（连续回放失败计数，达到 2 次自动弃用）
 */
@Entity(
    tableName = "action_macros",
    indices = [
        Index(value = ["intent_signature", "app_package", "target_app_version_code"], name = "idx_macro_lookup"),
        Index(value = ["last_used_at_ms"], name = "idx_macro_last_used"),
        Index(value = ["is_deprecated"], name = "idx_macro_deprecated")
    ]
)
data class ActionMacroEntity(
    @PrimaryKey
    @ColumnInfo(name = "macro_id")
    val macroId: String,

    /** 意图签名（如 "PLAY_MUSIC" / "OPEN_SEARCH_RESULT"），检索主键之一 */
    @ColumnInfo(name = "intent_signature")
    val intentSignature: String,

    @ColumnInfo(name = "app_package")
    val appPackage: String,

    /** 目标应用版本号：版本升级直接失效（§5.2 淘汰纪律） */
    @ColumnInfo(name = "target_app_version_code")
    val targetAppVersionCode: Long,

    /** 起始页抗噪拓扑指纹：回放第一步前必须核验（防暴走） */
    @ColumnInfo(name = "start_page_fingerprint")
    val startPageFingerprint: Long,

    /** 归一化坐标 + ref_id 复合动作序列（JSON） */
    @ColumnInfo(name = "steps_json")
    val stepsJson: String,

    /** 显式槽位键列表（JSON 数组），仅支持显式槽位 */
    @ColumnInfo(name = "explicit_slot_keys")
    val explicitSlotKeys: String,

    @ColumnInfo(name = "success_count")
    val successCount: Int = 0,

    @ColumnInfo(name = "failure_count")
    val failureCount: Int = 0,

    /** 连续失败计数：成功即归零；达到阈值自动弃用 */
    @ColumnInfo(name = "consecutive_failures")
    val consecutiveFailures: Int = 0,

    @ColumnInfo(name = "is_deprecated")
    val isDeprecated: Boolean = false,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "last_used_at_ms")
    val lastUsedAtMs: Long
) {
    companion object {
        /** 连续回放失败自动弃用阈值（§5.2） */
        const val DEPRECATE_AFTER_CONSECUTIVE_FAILURES = 2
    }
}
