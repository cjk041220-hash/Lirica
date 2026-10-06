package com.lirica.android.automation.macro

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.dao.ActionMacroDao
import com.lirica.android.database.entity.ActionMacroEntity
import java.util.UUID

/**
 * 经验轨迹库（TrajectoryMemory · M5.3 §5.2）。
 *
 * 契约纪律：
 * 1. **核验前置**：任何回放都必须先过起始页指纹核验，本接口不提供"直接取用"的入口；
 * 2. **版本失效**：目标应用版本升级的宏在查询层即被排除，不依赖事后清理；
 * 3. **淘汰闭环**：连续失败达阈值自动弃用，避免"越错越试"的负向循环；
 * 4. **单机自学**：宏只在本机录制、本机使用，不做任何云端上传（人类架构师定案）。
 */
interface TrajectoryMemory {

    /** 录制一条成功轨迹；同签名+同版本+同起始页的旧宏被替换 */
    suspend fun record(spec: MacroRecordSpec, replacedMacroId: String? = null): ActionMacro?

    /** 检索可用宏（版本不一致 / 已弃用 / 结构非法者不返回） */
    suspend fun findUsable(
        intentSignature: String,
        appPackage: String,
        targetAppVersionCode: Long
    ): ActionMacro?

    /** 回放成功：成功计数 +1，连续失败清零 */
    suspend fun markReplaySuccess(macroId: String)

    /**
     * 回放失败：失败计数 +1。
     * @return true 表示已达弃用阈值并被标记弃用
     */
    suspend fun markReplayFailure(macroId: String): Boolean

    /** 版本升级淘汰：把该应用下非当前版本的宏整批弃用 */
    suspend fun deprecateStaleVersions(appPackage: String, currentVersionCode: Long): Int

    /** 容量治理：活跃宏超过上限时淘汰最久未使用的一条 */
    suspend fun enforceCapacity(maxActive: Int): Int

    suspend fun activeCount(): Int

    suspend fun purgeDeprecated(): Int
}

/**
 * Room 实现。
 */
class RoomTrajectoryMemory(
    private val dao: ActionMacroDao,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val maxActiveMacros: Int = DEFAULT_MAX_ACTIVE_MACROS,
    /** 单条宏的步数上限（§1.2.3 有界性）：录制端就拦住，避免存下注定无法整段回放的轨迹 */
    private val maxStepsPerMacro: Int = DEFAULT_MAX_STEPS_PER_MACRO
) : TrajectoryMemory {

    override suspend fun record(spec: MacroRecordSpec, replacedMacroId: String?): ActionMacro? {
        if (spec.steps.isEmpty()) return null
        if (spec.steps.size > maxStepsPerMacro) {
            LiricaLog.w(
                "TrajectoryMemory",
                "宏录制被拒绝：步数 " + spec.steps.size + " 超过上限 " + maxStepsPerMacro
            )
            return null
        }
        if (spec.steps.any { !it.isWellFormed() }) {
            LiricaLog.w("TrajectoryMemory", "宏录制被拒绝：存在结构非法的步骤（缺少落点或文本）")
            return null
        }
        val now = timeProvider()
        if (replacedMacroId != null) {
            // 同一意图的新轨迹覆盖旧轨迹：旧宏直接弃用，避免两条互相竞争
            dao.deprecate(replacedMacroId)
        }
        val macro = ActionMacro(
            macroId = UUID.randomUUID().toString(),
            intentSignature = spec.intentSignature,
            appPackage = spec.appPackage,
            targetAppVersionCode = spec.targetAppVersionCode,
            startPageFingerprint = spec.startPageFingerprint,
            steps = spec.steps,
            explicitSlotKeys = spec.explicitSlotKeys
        )
        dao.upsert(MacroCodec.toEntity(macro, now))
        // 学到"该应用当前版本"的新轨迹后，同应用的旧版本宏已不可能再命中
        // （查询层按版本过滤），留着只占容量——顺手淘汰，避免弃用行无声堆积
        dao.deprecateStaleVersions(spec.appPackage, spec.targetAppVersionCode)
        enforceCapacity(maxActiveMacros)
        return macro
    }

    override suspend fun findUsable(
        intentSignature: String,
        appPackage: String,
        targetAppVersionCode: Long
    ): ActionMacro? {
        val entity = dao.findUsable(intentSignature, appPackage, targetAppVersionCode) ?: return null
        val macro = MacroCodec.toDomain(entity)
        return if (macro.isReplayable) macro else null
    }

    override suspend fun markReplaySuccess(macroId: String) {
        dao.markSuccess(macroId, timeProvider())
    }

    override suspend fun markReplayFailure(macroId: String): Boolean {
        dao.markFailure(macroId, timeProvider())
        val entity = dao.findById(macroId) ?: return false
        if (entity.consecutiveFailures >= ActionMacroEntity.DEPRECATE_AFTER_CONSECUTIVE_FAILURES) {
            dao.deprecate(macroId)
            LiricaLog.i(
                "TrajectoryMemory",
                "宏 " + macroId + " 连续失败 " + entity.consecutiveFailures + " 次，已自动弃用"
            )
            return true
        }
        return false
    }

    override suspend fun deprecateStaleVersions(appPackage: String, currentVersionCode: Long): Int =
        dao.deprecateStaleVersions(appPackage, currentVersionCode)

    override suspend fun enforceCapacity(maxActive: Int): Int {
        val active = dao.countActive()
        if (active <= maxActive) return 0
        var removed = 0
        val overflow = active - maxActive
        val oldest = dao.oldestActive(overflow)
        for (entity in oldest) {
            dao.deprecate(entity.macroId)
            removed++
        }
        if (removed > 0) {
            LiricaLog.i("TrajectoryMemory", "宏容量治理：淘汰最久未使用的 " + removed + " 条（上限 " + maxActive + "）")
        }
        return removed
    }

    override suspend fun activeCount(): Int = dao.countActive()

    override suspend fun purgeDeprecated(): Int = dao.purgeDeprecated()

    companion object {
        /** 单机活跃宏上限：一台手机上值得自学的复合意图不会超过这个量级 */
        const val DEFAULT_MAX_ACTIVE_MACROS = 64

        /** 单条宏步数上限：与 ActionExecutionBudget.maxSteps 对齐（12 步有界公理） */
        const val DEFAULT_MAX_STEPS_PER_MACRO = 12
    }
}
