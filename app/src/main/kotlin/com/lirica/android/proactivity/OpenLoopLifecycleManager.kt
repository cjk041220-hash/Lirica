package com.lirica.android.proactivity

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.dao.OpenLoopDao
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.OpenLoopEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import java.util.UUID

/**
 * 开环记忆生命周期管理器 (Open Loop Lifecycle Manager - M4.2 核心)
 *
 * 核心职能：
 * 1. 管理对话中识别的未决事件生命周期：OPEN -> TRACKING -> TRIGGERED -> RESOLVED / EXPIRED；
 * 2. 结合到期窗口检测，向未完成念头池 (UnspokenThoughtDao) 注入高关怀度的第一人称关怀念头；
 * 3. 及时响应用户反馈并闭环核销，超期 72 小时自动优雅失效，杜绝重复唠叨与假关怀。
 */
class OpenLoopLifecycleManager(
    private val openLoopDao: OpenLoopDao,
    private val unspokenThoughtDao: UnspokenThoughtDao? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_TRIGGER_LEAD_MS = 2 * 3600 * 1000L // 提前 2 小时进入关注跟踪窗口
        const val DEFAULT_EXPIRY_DURATION_MS = 72 * 3600 * 1000L // 事项截止后 72 小时静默过期
    }

    /** 创建新的未决事件 */
    suspend fun createLoop(
        title: String,
        targetTimeMs: Long,
        leadTimeMs: Long = DEFAULT_TRIGGER_LEAD_MS,
        urgencyLevel: Int = 1,
        followUpPrompt: String? = null,
        sourceMemoryId: String? = null
    ): OpenLoopEntity {
        val nowMs = timeProvider()
        val triggerWindowStart = (targetTimeMs - leadTimeMs).coerceAtLeast(nowMs)
        val prompt = followUpPrompt?.takeIf { it.isNotBlank() }
            ?: "待会儿不是有「$title」嘛，准备得怎么样啦？"

        val entity = OpenLoopEntity(
            id = UUID.randomUUID().toString(),
            sourceMemoryId = sourceMemoryId,
            title = title,
            targetTimeMs = targetTimeMs,
            triggerWindowStartMs = triggerWindowStart,
            status = OpenLoopEntity.STATUS_OPEN,
            urgencyLevel = urgencyLevel,
            followUpPrompt = prompt,
            retryCount = 0,
            lastPromptedMs = 0L,
            createdAt = nowMs
        )
        openLoopDao.insert(entity)
        LiricaLog.i("OpenLoopManager", "创建开环追踪事项: $title, 预期时间: $targetTimeMs")
        return entity
    }

    /** 扫描已到期的开环事项，推进状态并投递念头池 */
    suspend fun checkAndPromoteDueLoops(nowMs: Long = timeProvider()): List<OpenLoopEntity> {
        val dueLoops = openLoopDao.getDueForTracking(nowMs)
        if (dueLoops.isEmpty()) return emptyList()

        val promoted = mutableListOf<OpenLoopEntity>()
        for (loop in dueLoops) {
            // 注入未完成念头池作为破冰活水
            if (unspokenThoughtDao != null && loop.status != OpenLoopEntity.STATUS_TRIGGERED) {
                val thought = UnspokenThoughtEntity(
                    id = UUID.randomUUID().toString(),
                    thought = loop.followUpPrompt,
                    triggerContext = "open_loop:${loop.id}",
                    urgency = (0.6f + loop.urgencyLevel * 0.15f).coerceAtMost(0.95f),
                    decayScore = 1.0f,
                    targetScenario = "breakpoint_care",
                    createdAt = nowMs,
                    expiryTime = loop.targetTimeMs + 24 * 3600 * 1000L,
                    status = UnspokenThoughtEntity.STATUS_PENDING
                )
                unspokenThoughtDao.insert(thought)
            }

            openLoopDao.markPrompted(loop.id, nowMs, OpenLoopEntity.STATUS_TRIGGERED)
            promoted.add(loop.copy(status = OpenLoopEntity.STATUS_TRIGGERED, lastPromptedMs = nowMs))
        }
        LiricaLog.i("OpenLoopManager", "已推进并触发 ${promoted.size} 条开环关怀事项")
        return promoted
    }

    /** 用户提及或完成事件，核销闭环 */
    suspend fun resolveLoop(id: String) {
        openLoopDao.updateStatus(id, OpenLoopEntity.STATUS_RESOLVED)
        LiricaLog.i("OpenLoopManager", "成功闭环核销事项: $id")
    }

    /** 标记超期未完成事项为过期 */
    suspend fun expireOverdueLoops(nowMs: Long = timeProvider()): Int {
        val threshold = nowMs - DEFAULT_EXPIRY_DURATION_MS
        val count = openLoopDao.expireOldLoops(threshold)
        if (count > 0) {
            LiricaLog.d("OpenLoopManager", "自动失效超期事项: $count 条")
        }
        return count
    }
}