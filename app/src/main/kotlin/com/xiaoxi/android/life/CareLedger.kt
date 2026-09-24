package com.xiaoxi.android.life

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

/**
 * 关心提示的冷却台账 (Care Ledger)。
 *
 * 只存"某类提示上次注入的时间"，用途单一：保证同一由头不在冷却期内重复开口。
 *
 * **为什么用 SharedPreferences 而不是 Room 表**：
 * Python 参考实现把它做成了一张 `care_ledger` 表（迁移 v19），但那是一个
 * 服务端项目——加表成本接近零。本项目是 Android + Room，加一张表意味着
 * 一次 schema 迁移（v2→v3）+ 迁移测试 + schema JSON 重新导出，而这里
 * 总共只有三条记录、单值读取、没有查询需求。为它付一次迁移的代价不划算。
 * 等后续需要动 schema（例如入梦改造加向量字段）时再合并迁移即可。
 */
interface CareLedger {

    /** 读取台账。实现应失败放行（返回空表而非抛异常）——宁可多关心，不因存储抖动丢感知。 */
    fun load(): Map<CareHintType, Instant>

    /** 记账（注入即记账）。失败只记日志，不上抛。 */
    fun record(type: CareHintType, at: Instant)
}

/**
 * 空台账：不记录、不冷却。
 *
 * 作为 [CareHintDetector] 的默认实现，让检测器的单测可以在零依赖下运行；
 * 若生产装配忘记注入真实台账，行为退化为"每次都提示"，
 * 属于可见的降级（话变多）而非静默失效（永不提示），符合保守方向。
 */
object NoOpCareLedger : CareLedger {
    override fun load(): Map<CareHintType, Instant> = emptyMap()
    override fun record(type: CareHintType, at: Instant) = Unit
}

/**
 * SharedPreferences 实现。
 */
class SharedPreferencesCareLedger(
    private val prefs: SharedPreferences
) : CareLedger {

    companion object {
        const val PREFS_NAME = "lirica_care_ledger"
        private const val KEY_PREFIX = "care_last_at_"

        fun create(context: Context): SharedPreferencesCareLedger =
            SharedPreferencesCareLedger(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    override fun load(): Map<CareHintType, Instant> {
        return runCatching {
            CareHintType.values().mapNotNull { type ->
                val millis = prefs.getLong(KEY_PREFIX + type.name, 0L)
                if (millis > 0L) type to Instant.ofEpochMilli(millis) else null
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    override fun record(type: CareHintType, at: Instant) {
        runCatching {
            prefs.edit().putLong(KEY_PREFIX + type.name, at.toEpochMilli()).apply()
        }
    }

    /** 供"重置关心冷却"这类调试入口使用 */
    fun clear() {
        runCatching {
            val editor = prefs.edit()
            CareHintType.values().forEach { editor.remove(KEY_PREFIX + it.name) }
            editor.apply()
        }
    }
}
