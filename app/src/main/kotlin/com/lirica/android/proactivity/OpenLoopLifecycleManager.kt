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
 * 1. 管理对话中识别的未决事件生命周期：OPEN -> TRIGGERED -> RESOLVED / EXPIRED
 *    （TRACKING 为预留状态，当前实现由 OPEN 直达 TRIGGERED，见 [checkAndPromoteDueLoops]）；
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

        /**
         * 组装开环事项的关怀话术。
         *
         * 统一带上"标题｜正文"结构（分隔符见 ProactiveBubbleCoordinator.TITLE_SEPARATOR）：
         * - 气泡/聊天直接展示整条，自然可读；
         * - L3 通知拆成通知标题 + 通知正文两行。
         * 同一句话在两种通道都成立，不必为通知另写一套文案。
         */
        fun buildFollowUpPrompt(title: String, followUpPrompt: String?): String {
            val body = followUpPrompt?.takeIf { it.isNotBlank() }
                ?: "待会儿不是有「$title」嘛，准备得怎么样啦？"
            return "$title${ProactiveBubbleCoordinator.TITLE_SEPARATOR}$body"
        }
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
        val prompt = buildFollowUpPrompt(title, followUpPrompt)

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
            // 先原子占坑再注入：条件更新只在 OPEN/TRACKING 态生效，返回 0 说明
            // 已被并发路径处理过，直接跳过——消除"无条件 UPDATE + 无 DB 防重"的双注入风险，
            // 正确性不再依赖调用方必须持有状态锁。
            val claimed = openLoopDao.markPromptedIfActive(loop.id, nowMs, OpenLoopEntity.STATUS_TRIGGERED)
            if (claimed == 0) continue

            // 注入未完成念头池作为破冰活水
            if (unspokenThoughtDao != null) {
                val thought = UnspokenThoughtEntity(
                    id = UUID.randomUUID().toString(),
                    thought = buildFollowUpPrompt(loop.title, loop.followUpPrompt),
                    triggerContext = "open_loop:${loop.id}",
                    // 念头池 urgency 统一 0~1 存储尺度：level 1→0.5、2→0.7、3→0.9。
                    // 0.9 恰好达到 L3 通知门槛（ProactiveBubbleCoordinator.L3_URGENCY_THRESHOLD）——
                    // 紧急事项息屏时可走系统通知；0.7×5=3.5 低于 3.6 打断阈值，重要不紧急事项只静静待在池中。
                    urgency = (0.3f + loop.urgencyLevel * 0.2f).coerceIn(0.5f, 0.9f),
                    decayScore = 1.0f,
                    targetScenario = "breakpoint_care",
                    createdAt = nowMs,
                    expiryTime = loop.targetTimeMs + 24 * 3600 * 1000L,
                    status = UnspokenThoughtEntity.STATUS_PENDING
                )
                unspokenThoughtDao.insert(thought)
            }

            promoted.add(loop.copy(status = OpenLoopEntity.STATUS_TRIGGERED, lastPromptedMs = nowMs))
        }
        if (promoted.isNotEmpty()) {
            LiricaLog.i("OpenLoopManager", "已推进并触发 ${promoted.size} 条开环关怀事项")
        }
        return promoted
    }

    /** 用户提及或完成事件，核销闭环 */
    suspend fun resolveLoop(id: String) {
        openLoopDao.updateStatus(id, OpenLoopEntity.STATUS_RESOLVED)
        LiricaLog.i("OpenLoopManager", "成功闭环核销事项: $id")
    }

    /**
     * 用户主动提到某件事 → 即时核销相关开环，并撤回已经推进到念头池的对应念头。
     *
     * 这是"别再唠叨"的关键一环：原实现只有 [resolveLoop] 这个**没有任何调用方**的方法，
     * 事项只能靠 72 小时超时消失；用户明明已经考完了，她还会在截止后继续追问。
     *
     * @return 被核销的开环条目
     */
    suspend fun resolveMentionedLoops(userText: String, nowMs: Long = timeProvider()): List<OpenLoopEntity> {
        if (userText.isBlank()) return emptyList()
        val resolved = mutableListOf<OpenLoopEntity>()
        // 标题长度下限 2：单字标题（"睡""考"）子串误命中率高（如"睡不着""备考"），
        // 宁可漏核销等 72h 过期，也不误杀用户还惦记着的事项。
        val candidates = openLoopDao.getOpenLoops().filter {
            it.title.isNotBlank() && it.title.length >= 2 && userText.contains(it.title)
        }
        for (loop in candidates) {
            openLoopDao.updateStatus(loop.id, OpenLoopEntity.STATUS_RESOLVED)
            retractOpenLoopThought(loop, nowMs)
            resolved.add(loop)
        }
        if (resolved.isNotEmpty()) {
            LiricaLog.i("OpenLoopManager", "用户已提及，核销开环 ${resolved.size} 条: ${resolved.map { it.title }}")
        }
        return resolved
    }

    /**
     * 撤回某条开环对应的待表达念头。
     *
     * 念头是用 UUID 主键、不带 title 字段的，只有 [UnspokenThoughtEntity.triggerContext]
     * 记着 "open_loop:<loopId>"，因此用前缀匹配定位——避免误伤普通感知念头。
     */
    private suspend fun retractOpenLoopThought(loop: OpenLoopEntity, nowMs: Long) {
        val prefix = "open_loop:${loop.id}"
        unspokenThoughtDao?.getByTriggerContextPrefix(prefix)
            ?.forEach { unspokenThoughtDao.deleteById(it.id) }
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