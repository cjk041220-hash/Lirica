package com.lirica.android.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lirica.android.database.entity.ActionMacroEntity

/**
 * 机型自适应经验轨迹表访问接口（ActionMacroDao · DB v6）。
 *
 * 纪律：所有"标记成功/失败"都是原子 UPDATE 而非读改写，
 * 避免并发任务与后台淘汰同时操作同一条宏时互相覆盖计数。
 */
@Dao
interface ActionMacroDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ActionMacroEntity)

    /**
     * 检索可用宏：意图签名 + 包名 + **版本号完全一致** + 未弃用。
     * 版本号参与 WHERE 是 §5.2「目标 App 版本升级直接失效」的落地方式——
     * 靠淘汰任务事后清理会有时间窗，靠查询条件排除才是硬保证。
     */
    @Query(
        "SELECT * FROM action_macros WHERE intent_signature = :signature AND app_package = :appPackage " +
            "AND target_app_version_code = :versionCode AND is_deprecated = 0 " +
            "ORDER BY success_count DESC, last_used_at_ms DESC LIMIT 1"
    )
    suspend fun findUsable(signature: String, appPackage: String, versionCode: Long): ActionMacroEntity?

    @Query("SELECT * FROM action_macros WHERE macro_id = :macroId LIMIT 1")
    suspend fun findById(macroId: String): ActionMacroEntity?

    @Query(
        "UPDATE action_macros SET success_count = success_count + 1, consecutive_failures = 0, " +
            "last_used_at_ms = :nowMs WHERE macro_id = :macroId"
    )
    suspend fun markSuccess(macroId: String, nowMs: Long)

    @Query(
        "UPDATE action_macros SET failure_count = failure_count + 1, " +
            "consecutive_failures = consecutive_failures + 1, last_used_at_ms = :nowMs " +
            "WHERE macro_id = :macroId"
    )
    suspend fun markFailure(macroId: String, nowMs: Long)

    @Query("UPDATE action_macros SET is_deprecated = 1 WHERE macro_id = :macroId")
    suspend fun deprecate(macroId: String)

    /** 版本升级淘汰：把非当前版本的同应用宏整批弃用（后台维护任务调用） */
    @Query(
        "UPDATE action_macros SET is_deprecated = 1 WHERE app_package = :appPackage " +
            "AND target_app_version_code != :currentVersionCode AND is_deprecated = 0"
    )
    suspend fun deprecateStaleVersions(appPackage: String, currentVersionCode: Long): Int

    @Query("SELECT * FROM action_macros WHERE is_deprecated = 0 ORDER BY last_used_at_ms ASC LIMIT :limit")
    suspend fun oldestActive(limit: Int): List<ActionMacroEntity>

    @Query("DELETE FROM action_macros WHERE macro_id = :macroId")
    suspend fun deleteById(macroId: String)

    @Query("DELETE FROM action_macros WHERE is_deprecated = 1")
    suspend fun purgeDeprecated(): Int

    @Query("SELECT COUNT(*) FROM action_macros WHERE is_deprecated = 0")
    suspend fun countActive(): Int
}
